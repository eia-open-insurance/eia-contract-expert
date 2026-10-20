package com.eia.contract.expert.cashcall;

import com.eia.contract.expert.BusinessKeys;
import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractEvents;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.ResourceNotFoundException;
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
 * Contract cash-call API.
 *
 * <p>The brief names {@code /api/v1/contracts/{contractId}/cash-calls}; the
 * expert's internal prefix is mapped to the same controller for parity with the
 * technical-account slice. The tenant is carried in {@code X-EIA-Tenant} (public
 * convention) or the {@code tenantId} query parameter used by the existing
 * internal endpoints. Every query is scoped by tenant and contract, which is the
 * contract wall: a cash call of another tenant or another contract is reported as
 * 404.</p>
 *
 * <p>Create and status changes append their event through the transactional
 * outbox in the same transaction as the row write. A rolled-back request leaves
 * neither the cash call, the business key, nor the event behind.</p>
 */
@RestController
@RequestMapping({
        "/api/v1/contracts/{contractId}/cash-calls",
        "/internal/v1/contracts/{contractId}/cash-calls"
})
public class CashCallController {

    private static final String CASH_CALL_PREFIX = "CC";

    private final ContractRepository contracts;
    private final CashCallRepository cashCalls;
    private final BusinessKeys keys;
    private final OutboxRepository outbox;

    public CashCallController(ContractRepository contracts,
                              CashCallRepository cashCalls,
                              BusinessKeys keys,
                              OutboxRepository outbox) {
        this.contracts = contracts;
        this.cashCalls = cashCalls;
        this.keys = keys;
        this.outbox = outbox;
    }

    @GetMapping
    public List<ContractCashCall> list(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        requireContract(tenantId, contractId);
        return cashCalls.findAll(tenantId, contractId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ContractCashCall create(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId,
            @RequestBody CreateCashCallRequest request) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        // The contract row lock serializes call-number generation for this
        // contract without relying on the unique index alone.
        Contract contract = requireContractForUpdate(tenantId, contractId);
        CashCallRules.CashCallValues values = CashCallRules.validate(request);
        String callNumber = keys.nextPortable(tenantId, CASH_CALL_PREFIX,
                values.periodStart().getYear());
        ContractCashCall cashCall = ContractCashCall.requested(
                tenantId, contractId, callNumber, values.periodStart(), values.periodEnd(),
                values.amount(), values.currency(), values.dueDate(), values.notes());
        ContractCashCall created = cashCalls.create(cashCall);
        publish(ContractEvents.cashCallRequested(contract, created, Instant.now()), contractId);
        return created;
    }

    @PatchMapping("/{cashCallId}/status")
    @Transactional
    public ContractCashCall updateStatus(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId,
            @PathVariable UUID cashCallId,
            @RequestBody UpdateCashCallStatusRequest request) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        Contract contract = requireContract(tenantId, contractId);
        ContractCashCall current = cashCalls.findById(tenantId, contractId, cashCallId)
                .orElseThrow(() -> new ResourceNotFoundException("Cash call", cashCallId));
        CashCallStatus from = CashCallRules.currentStatus(current.status());
        CashCallStatus target = CashCallRules.parseTargetStatus(request.status());
        CashCallRules.validateTransition(from, target);

        ContractCashCall updated = cashCalls.updateStatus(
                tenantId, contractId, cashCallId, from, target);
        Instant now = Instant.now();
        EventEnvelope event = switch (target) {
            case ISSUED -> ContractEvents.cashCallIssued(contract, updated, now);
            case SETTLED -> ContractEvents.cashCallSettled(contract, updated, now);
            case CANCELLED -> ContractEvents.cashCallCancelled(contract, updated, now);
            default -> throw new IllegalStateException("Unexpected cash-call target " + target);
        };
        publish(event, contractId);
        return updated;
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
