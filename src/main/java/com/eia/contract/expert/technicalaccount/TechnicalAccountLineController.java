package com.eia.contract.expert.technicalaccount;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
 * Technical-account line API (the detail behind the account header).
 *
 * <p>The resource hangs off the account under the brief's public path and the
 * expert's internal prefix. The tenant is carried in {@code X-EIA-Tenant} or the
 * {@code tenantId} query parameter. Every query is scoped by tenant, contract,
 * and account, so a line of another tenant or another account is a 404. The
 * account view itself deliberately omits lines; this endpoint is the detail
 * read model and returns the per-type totals the issue reconciliation uses.</p>
 *
 * <p>Lines can only be added or removed while the account is {@code DRAFT};
 * otherwise the request is a 409 {@code ACCOUNT_NOT_EDITABLE}. Each mutation
 * appends its event through the transactional outbox in the same transaction as
 * the row write, so a rollback leaves neither the line nor the event behind.</p>
 */
@RestController
@RequestMapping({
        "/api/v1/contracts/{contractId}/technical-accounts/{accountId}/lines",
        "/internal/v1/contracts/{contractId}/technical-accounts/{accountId}/lines"
})
public class TechnicalAccountLineController {

    private final ContractRepository contracts;
    private final TechnicalAccountRepository accounts;
    private final TechnicalAccountLineRepository lines;
    private final OutboxRepository outbox;

    public TechnicalAccountLineController(ContractRepository contracts,
                                          TechnicalAccountRepository accounts,
                                          TechnicalAccountLineRepository lines,
                                          OutboxRepository outbox) {
        this.contracts = contracts;
        this.accounts = accounts;
        this.lines = lines;
        this.outbox = outbox;
    }

    @GetMapping
    public TechnicalAccountLineList list(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId,
            @PathVariable UUID accountId) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        requireContract(tenantId, contractId);
        requireAccount(tenantId, contractId, accountId);

        List<ContractTechnicalAccountLine> detail = lines.findAll(tenantId, accountId);
        return new TechnicalAccountLineList(accountId, detail,
                TechnicalAccountLineRules.formattedTotals(
                        TechnicalAccountLineRules.sumLines(detail)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ContractTechnicalAccountLine add(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId,
            @PathVariable UUID accountId,
            @RequestBody CreateTechnicalAccountLineRequest request) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        Contract contract = requireContract(tenantId, contractId);
        accounts.lockById(tenantId, contractId, accountId);
        ContractTechnicalAccount account = requireAccount(tenantId, contractId, accountId);
        TechnicalAccountLineRules.requireDraft(account);

        TechnicalAccountLineRules.LineValues values = TechnicalAccountLineRules.validate(request);
        ContractTechnicalAccountLine created = lines.create(ContractTechnicalAccountLine.create(
                tenantId, accountId, values.lineType(), values.reference(),
                values.description(), values.amount()));
        publish(ContractEvents.technicalAccountLineAdded(contract, account, created, Instant.now()),
                contractId);
        return created;
    }

    @DeleteMapping("/{lineId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void remove(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId,
            @PathVariable UUID accountId,
            @PathVariable UUID lineId) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        Contract contract = requireContract(tenantId, contractId);
        accounts.lockById(tenantId, contractId, accountId);
        ContractTechnicalAccount account = requireAccount(tenantId, contractId, accountId);
        TechnicalAccountLineRules.requireDraft(account);

        ContractTechnicalAccountLine line = lines.findById(tenantId, accountId, lineId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Technical account line", lineId));
        if (!lines.delete(tenantId, accountId, lineId)) {
            throw new ResourceNotFoundException("Technical account line", lineId);
        }
        publish(ContractEvents.technicalAccountLineRemoved(contract, account, line, Instant.now()),
                contractId);
    }

    private Contract requireContract(UUID tenantId, UUID contractId) {
        return contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    private ContractTechnicalAccount requireAccount(UUID tenantId, UUID contractId,
                                                    UUID accountId) {
        return accounts.findById(tenantId, contractId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Technical account", accountId));
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
