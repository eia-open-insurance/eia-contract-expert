package com.eia.contract.expert.technicalaccount;

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
 * Contract technical-account API.
 *
 * <p>The brief names {@code /api/v1/contracts/{contractId}/technical-accounts};
 * the expert's sibling slices also expose {@code /internal/v1/...}, so both
 * prefixes are mapped to the same controller. A request carries the tenant in
 * {@code X-EIA-Tenant} (the public API convention) or in the {@code tenantId}
 * query parameter used by the existing internal endpoints. Every query is scoped
 * by tenant and contract, which is the contract wall: an account of another
 * tenant or another contract is reported as 404.</p>
 *
 * <p>Create and status changes append their event through the transactional
 * outbox in the same transaction as the row write. A rolled-back request leaves
 * neither the account nor the event behind. Issuing a DRAFT account also
 * reconciles its line detail against the header: when any line exists, every
 * line-type total must equal the matching header component, otherwise the
 * transition is rejected with the per-type mismatch details.</p>
 */
@RestController
@RequestMapping({
        "/api/v1/contracts/{contractId}/technical-accounts",
        "/internal/v1/contracts/{contractId}/technical-accounts"
})
public class TechnicalAccountController {

    private static final String ACCOUNT_PREFIX = "TA";

    private final ContractRepository contracts;
    private final TechnicalAccountRepository accounts;
    private final TechnicalAccountLineRepository lines;
    private final BusinessKeys keys;
    private final OutboxRepository outbox;

    public TechnicalAccountController(ContractRepository contracts,
                                      TechnicalAccountRepository accounts,
                                      TechnicalAccountLineRepository lines,
                                      BusinessKeys keys,
                                      OutboxRepository outbox) {
        this.contracts = contracts;
        this.accounts = accounts;
        this.lines = lines;
        this.keys = keys;
        this.outbox = outbox;
    }

    @GetMapping
    public List<ContractTechnicalAccount> list(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        requireContract(tenantId, contractId);
        return accounts.findAll(tenantId, contractId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ContractTechnicalAccount create(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId,
            @RequestBody CreateTechnicalAccountRequest request) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        // The contract row lock serializes period uniqueness and business-key
        // generation for this contract without relying on the unique index alone.
        Contract contract = requireContractForUpdate(tenantId, contractId);
        return createForContract(contract, request);
    }

    /**
     * Creates the DRAFT account and appends its event for an already-resolved,
     * tenant-scoped and locked contract. A period bordereau calls this inside its
     * own transaction so the account and the bordereau commit or roll back
     * together.
     */
    @Transactional
    public ContractTechnicalAccount createForContract(Contract contract,
                                                      CreateTechnicalAccountRequest request) {
        TechnicalAccountRules.PeriodAmounts values = TechnicalAccountRules.validate(request);
        String accountNumber = keys.nextPortable(contract.tenantId(), ACCOUNT_PREFIX,
                values.periodStart().getYear());
        ContractTechnicalAccount account = ContractTechnicalAccount.draft(
                contract.tenantId(), contract.id(), accountNumber,
                values.periodStart(), values.periodEnd(), values.currency(),
                values.cededPremium(), values.cededLosses(), values.commission(),
                values.otherCharges(), values.balance());
        ContractTechnicalAccount created = accounts.create(account);
        publish(ContractEvents.technicalAccountCreated(contract, created, Instant.now()),
                contract.id());
        return created;
    }

    @PatchMapping("/{accountId}/status")
    @Transactional
    public ContractTechnicalAccount updateStatus(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId,
            @PathVariable UUID accountId,
            @RequestBody UpdateTechnicalAccountStatusRequest request) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        Contract contract = requireContract(tenantId, contractId);
        ContractTechnicalAccount current = accounts.findById(tenantId, contractId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Technical account", accountId));
        TechnicalAccountStatus from = TechnicalAccountRules.currentStatus(current.status());
        TechnicalAccountStatus target = TechnicalAccountRules.parseTargetStatus(request.status());
        TechnicalAccountRules.validateTransition(from, target);
        if (target == TechnicalAccountStatus.ISSUED) {
            // Lock the account so a concurrent line add/remove cannot commit
            // between reading the totals and writing ISSUED. Re-read and
            // re-validate after the lock in case another transition won.
            accounts.lockById(tenantId, contractId, accountId);
            current = accounts.findById(tenantId, contractId, accountId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Technical account", accountId));
            from = TechnicalAccountRules.currentStatus(current.status());
            TechnicalAccountRules.validateTransition(from, target);
            // A header-only account (created before this slice or by a period
            // bordereau) has no rows and stays issuable; any line detail must
            // reconcile type by type before the statement becomes immutable.
            TechnicalAccountLineRules.reconcile(current,
                    lines.totals(tenantId, accountId));
        }

        ContractTechnicalAccount updated = accounts.updateStatus(
                tenantId, contractId, accountId, from, target);
        Instant now = Instant.now();
        EventEnvelope event = switch (target) {
            case ISSUED -> ContractEvents.technicalAccountIssued(contract, updated, now);
            case SETTLED -> ContractEvents.technicalAccountSettled(contract, updated, now);
            default -> throw new IllegalStateException("Unexpected technical-account target " + target);
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
