package com.eia.contract.expert.bordereau;

import com.eia.contract.expert.BusinessKeys;
import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractEvents;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.ResourceNotFoundException;
import com.eia.contract.expert.technicalaccount.ContractTechnicalAccount;
import com.eia.contract.expert.technicalaccount.CreateTechnicalAccountRequest;
import com.eia.contract.expert.technicalaccount.TechnicalAccountController;
import com.eia.contract.expert.technicalaccount.TechnicalAccountRepository;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Period-bordereau API.
 *
 * <p>The brief names {@code /api/v1/contracts/{contractId}/bordereaux}. The
 * legacy ingest header keeps the expert's internal prefix
 * ({@link BordereauHeaderController}), so this controller owns only the public
 * path. The tenant is carried in {@code X-EIA-Tenant} (public convention) or the
 * {@code tenantId} query parameter used by the internal endpoints; every query is
 * scoped by tenant and contract, which is the contract wall: a bordereau of
 * another tenant or another contract is reported as 404.</p>
 *
 * <p>Create and status changes append their event through the transactional
 * outbox in the same transaction as the row write. Processing either reuses the
 * technical account for the same contract period or creates one through the
 * technical-account slice, so the account event and the bordereau event commit
 * or roll back together. A rolled-back request leaves neither the row, the
 * account, the business key, nor the event behind.</p>
 */
@RestController
@RequestMapping("/api/v1/contracts/{contractId}/bordereaux")
public class BordereauController {

    private static final String BORDEREAU_PREFIX = "BRD";

    private final ContractRepository contracts;
    private final BordereauRepository bordereaux;
    private final TechnicalAccountRepository accounts;
    private final TechnicalAccountController technicalAccounts;
    private final BusinessKeys keys;
    private final OutboxRepository outbox;

    public BordereauController(ContractRepository contracts,
                               BordereauRepository bordereaux,
                               TechnicalAccountRepository accounts,
                               TechnicalAccountController technicalAccounts,
                               BusinessKeys keys,
                               OutboxRepository outbox) {
        this.contracts = contracts;
        this.bordereaux = bordereaux;
        this.accounts = accounts;
        this.technicalAccounts = technicalAccounts;
        this.keys = keys;
        this.outbox = outbox;
    }

    @GetMapping
    public List<ContractBordereau> list(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        requireContract(tenantId, contractId);
        return bordereaux.findAll(tenantId, contractId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ContractBordereau create(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId,
            @RequestBody CreateBordereauRequest request) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        // The contract row lock serializes period uniqueness and business-key
        // generation for this contract without relying on the unique index alone.
        Contract contract = requireContractForUpdate(tenantId, contractId);
        BordereauRules.BordereauValues values = BordereauRules.validate(request);
        String bordereauNumber = keys.nextPortable(tenantId, BORDEREAU_PREFIX,
                values.periodStart().getYear());
        ContractBordereau bordereau = ContractBordereau.received(
                tenantId, contractId, bordereauNumber, values.periodStart(), values.periodEnd(),
                values.receivedDate(), values.currency(), values.premiumAmount(),
                values.lossAmount(), values.commissionAmount(), values.otherChargesAmount(),
                values.lineCount(), values.notes());
        ContractBordereau created = bordereaux.create(bordereau);
        publish(ContractEvents.bordereauReceived(contract, created, Instant.now()), contractId);
        return created;
    }

    @PatchMapping("/{bordereauId}/status")
    @Transactional
    public ContractBordereau updateStatus(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId,
            @PathVariable UUID bordereauId,
            @RequestBody UpdateBordereauStatusRequest request) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        // The lock also serializes technical-account creation for the period.
        Contract contract = requireContractForUpdate(tenantId, contractId);
        ContractBordereau current = bordereaux.findById(tenantId, contractId, bordereauId)
                .orElseThrow(() -> new ResourceNotFoundException("Bordereau", bordereauId));

        BordereauStatus from = BordereauRules.currentStatus(current.status());
        BordereauStatus target = BordereauRules.parseTargetStatus(request.status());
        BordereauRules.validateTransition(from, target);

        UUID technicalAccountId = target == BordereauStatus.PROCESSED
                ? technicalAccountFor(contract, current).id()
                : null;
        ContractBordereau updated = bordereaux.updateStatus(
                tenantId, contractId, bordereauId, from, target,
                BordereauRules.notes(request.notes()), technicalAccountId);

        EventEnvelope event = target == BordereauStatus.PROCESSED
                ? ContractEvents.bordereauProcessed(contract, updated, Instant.now())
                : ContractEvents.bordereauFailed(contract, updated, Instant.now());
        publish(event, contractId);
        return updated;
    }

    /**
     * Reuses the technical account already recorded for the same contract period,
     * otherwise creates the DRAFT account with the bordereau amounts. The caller
     * holds the contract row lock, so the lookup and the create cannot race with
     * another bordereau or account write for the period.
     */
    private ContractTechnicalAccount technicalAccountFor(Contract contract,
                                                         ContractBordereau bordereau) {
        return accounts.findForPeriod(contract.tenantId(), contract.id(),
                        bordereau.periodStart(), bordereau.periodEnd())
                .orElseGet(() -> technicalAccounts.createForContract(contract,
                        new CreateTechnicalAccountRequest(
                                bordereau.periodStart(), bordereau.periodEnd(),
                                bordereau.currency(), bordereau.premiumAmount(),
                                bordereau.lossAmount(), bordereau.commissionAmount(),
                                bordereau.otherChargesAmount())));
    }

    private Contract requireContract(UUID tenantId, UUID contractId) {
        return contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    private Contract requireContractForUpdate(UUID tenantId, UUID contractId) {
        return contracts.findByIdForUpdate(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    private void publish(EventEnvelope event, UUID contractId) {
        outbox.insert(OutboxEvent.fromEnvelope(event, contractId.toString()));
    }

    private static UUID resolveTenant(UUID headerTenant, UUID queryTenant) {
        if (headerTenant != null && queryTenant != null && !headerTenant.equals(queryTenant)) {
            throw new ContractValidationException("TENANT_MISMATCH", "tenantId",
                    "X-EIA-Tenant and tenantId must identify the same tenant");
        }
        UUID tenantId = headerTenant != null ? headerTenant : queryTenant;
        if (tenantId == null) {
            throw new ContractValidationException("TENANT_REQUIRED", "tenantId",
                    "X-EIA-Tenant header or tenantId query parameter is required");
        }
        return tenantId;
    }
}
