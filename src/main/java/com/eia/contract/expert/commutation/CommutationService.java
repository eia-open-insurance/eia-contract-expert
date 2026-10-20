package com.eia.contract.expert.commutation;

import com.eia.contract.expert.BusinessKeys;
import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractEvents;
import com.eia.contract.expert.ContractGuard;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.ContractStatus;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.Values;
import com.eia.contract.expert.security.SecurityRepository;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Commutation commands (SPEC 11.3.8 {@code contract.commute}, SPEC 7.21.1).
 *
 * <p>Proposal records the negotiated amount; approval records the approver;
 * execution requires a release document, releases the contract's security, and
 * closes the contract. A {@code FULL} commutation walks the legal
 * {@code COMMUTED -> CLOSED} edge, while the other types leave the contract in
 * {@code COMMUTED} so run-off can continue.</p>
 */
@Service
public class CommutationService {

    private final ContractRepository contracts;
    private final CommutationRepository commutations;
    private final SecurityRepository securities;
    private final BusinessKeys keys;
    private final OutboxRepository outbox;

    public CommutationService(ContractRepository contracts, CommutationRepository commutations,
                              SecurityRepository securities, BusinessKeys keys,
                              OutboxRepository outbox) {
        this.contracts = contracts;
        this.commutations = commutations;
        this.securities = securities;
        this.keys = keys;
        this.outbox = outbox;
    }

    @Transactional
    public ContractCommutation create(UUID tenantId, UUID contractId, CommutationController.CreateCommutationRequest request) {
        Contract contract = contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
        ContractGuard.requireCommutationProposable(contract);
        String type = request.commutationType() == null || request.commutationType().isBlank()
                ? "FULL"
                : Values.enumOf(CommutationType.class, request.commutationType(), "commutationType").name();
        String basis = request.basis() == null || request.basis().isBlank()
                ? "NEGOTIATED"
                : Values.enumOf(CommutationBasis.class, request.basis(), "basis").name();
        LocalDate effectiveDate = request.effectiveDate();
        LocalDate valuationDate = request.valuationDate();
        if (effectiveDate == null || valuationDate == null) {
            throw new ContractValidationException("INVALID_COMMUTATION_DATES", "effectiveDate",
                    "effectiveDate and valuationDate are required");
        }
        if (valuationDate.isAfter(effectiveDate)) {
            throw new ContractConflictException("INVALID_COMMUTATION_DATES",
                    "valuationDate " + valuationDate + " is after effectiveDate " + effectiveDate,
                    "valuationDate", "A commutation is valued on or before it takes effect");
        }
        BigDecimal agreedAmount = Values.money(request.agreedAmount(), "agreedAmount", true);
        BigDecimal savingsAmount = Values.money(request.savingsAmount(), "savingsAmount", false);
        String currency = request.currency() == null || request.currency().isBlank()
                ? contract.currency()
                : Values.currency(request.currency(), "currency");
        if (!currency.equals(contract.currency())) {
            throw new ContractConflictException("COMMUTATION_CURRENCY_MISMATCH",
                    "Commutation currency " + currency + " does not match contract currency "
                            + contract.currency(),
                    "currency", "SPEC CTR-17 keeps the commutation in the contract currency");
        }
        String number = request.commutationNumber() == null || request.commutationNumber().isBlank()
                ? keys.next(tenantId, "COM", effectiveDate.getYear())
                : Values.text(request.commutationNumber(), "commutationNumber", 32);
        return commutations.create(ContractCommutation.create(tenantId, contractId, number, type,
                effectiveDate, valuationDate, agreedAmount, currency, basis,
                request.releaseDocumentId(), request.paymentId(), savingsAmount));
    }

    @Transactional
    public ContractCommutation approve(UUID tenantId, UUID contractId, UUID commutationId, UUID approvedBy) {
        requireContract(tenantId, contractId);
        if (approvedBy == null) {
            throw new ContractValidationException("COMMUTATION_APPROVAL_REQUIRED", "approvedBy",
                    "A commutation approval requires the approver's id (SPEC COM-01)");
        }
        return commutations.approve(tenantId, contractId, commutationId, approvedBy);
    }

    @Transactional
    public Execution execute(UUID tenantId, UUID contractId, UUID commutationId,
                             CommutationController.ExecuteCommutationRequest request) {
        Contract contract = contracts.findByIdForUpdate(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
        ContractGuard.requireCommutationExecutable(contract);
        ContractCommutation commutation = commutations.get(tenantId, contractId, commutationId);
        if (!"APPROVED".equals(commutation.status())) {
            throw new ContractConflictException("COMMUTATION_NOT_APPROVED",
                    "Commutation " + commutation.commutationNumber() + " is " + commutation.status()
                            + "; it must be APPROVED before it can be executed",
                    "status", "Expected APPROVED");
        }
        UUID releaseDocumentId = request == null || request.releaseDocumentId() == null
                ? commutation.releaseDocumentId()
                : request.releaseDocumentId();
        if (releaseDocumentId == null) {
            throw new ContractConflictException("COMMUTATION_RELEASE_REQUIRED",
                    "A signed release document is required before execution (SPEC CTR-12)",
                    "releaseDocumentId", "Attach the release document on create or execute");
        }
        if (commutations.hasExecuted(tenantId, contractId)) {
            throw new ContractConflictException("COMMUTATION_ALREADY_EXECUTED",
                    "This contract already has an executed commutation",
                    "commutationId", "Only one commutation can be executed per contract");
        }
        LocalDate releaseDate = request == null || request.releaseDate() == null
                ? commutation.effectiveDate()
                : request.releaseDate();
        ContractCommutation executed = commutations.execute(tenantId, contractId, commutationId,
                releaseDocumentId, releaseDate);
        int released = securities.releaseAll(tenantId, contractId, releaseDate);
        boolean closes = "FULL".equals(commutation.commutationType());
        Instant now = Instant.now();
        ContractStatus target = closes ? ContractStatus.CLOSED : ContractStatus.COMMUTED;
        Contract updated = contracts.applyCommutation(tenantId, contractId, target, now,
                closes ? now : null, contract.version());
        publish(ContractEvents.commutationExecuted(
                contract, updated, executed, released, now));
        return new Execution(executed, updated, released, closes);
    }

    private void publish(List<EventEnvelope> events) {
        for (EventEnvelope event : events) {
            outbox.insert(OutboxEvent.fromEnvelope(event));
        }
    }

    private Contract requireContract(UUID tenantId, UUID contractId) {
        return contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    public enum CommutationType {
        FULL, PARTIAL, LOSS_PORTFOLIO, BUYOUT, NOVATION
    }

    public enum CommutationBasis {
        NEGOTIATED, CONTRACTUAL, INDUCED, REGULATORY, INSOLVENCY
    }

    /** Result of executing a commutation: the record, the contract and the closure. */
    public record Execution(ContractCommutation commutation, Contract contract,
                            int releasedSecurities, boolean contractClosed) {}
}
