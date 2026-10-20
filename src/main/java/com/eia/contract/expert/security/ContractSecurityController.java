package com.eia.contract.expert.security;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractEvents;
import com.eia.contract.expert.ContractGuard;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.ContractValidationException;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
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
 * Public contract security/collateral API used by the contract-web Security tab.
 *
 * <p>The tenant travels in {@code X-EIA-Tenant}, with the {@code tenantId} query
 * parameter kept for the internal convention. Every read and write is scoped by
 * both tenant and contract, so a row of another tenant or contract is reported as
 * 404. A create appends {@code contract.security.recorded} through the
 * transactional outbox in the same transaction; a rollback leaves neither the
 * security row nor the event behind.</p>
 *
 * <p>The new endpoint derives the security status ({@code ACTIVE}, or
 * {@code EXPIRED} when the expiry date is already past) and does not expose a
 * status action. The older {@link SecurityController} internal endpoint is kept
 * for the bind and commutation slices.</p>
 */
@RestController
@RequestMapping("/api/v1/contracts/{contractId}/security")
public class ContractSecurityController {

    private final ContractRepository contracts;
    private final SecurityRepository securities;
    private final OutboxRepository outbox;

    public ContractSecurityController(ContractRepository contracts,
                                      SecurityRepository securities,
                                      OutboxRepository outbox) {
        this.contracts = contracts;
        this.securities = securities;
        this.outbox = outbox;
    }

    /** Newest collateral record first. */
    @GetMapping
    public List<ContractSecurityView> list(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        requireContract(tenantId, contractId);
        return securities.findAll(tenantId, contractId).stream()
                .map(ContractSecurityView::of)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ContractSecurityView create(
            @RequestHeader(name = "X-EIA-Tenant", required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID contractId,
            @RequestBody CreateContractSecurityRequest request) {
        UUID tenantId = resolveTenant(headerTenant, queryTenant);
        Contract contract = requireContract(tenantId, contractId);
        ContractGuard.requireEditable(contract);
        ContractSecurity created = securities.create(
                ContractSecurityRules.validate(contract, request));
        publish(ContractEvents.securityRecorded(contract, created, Instant.now()), contractId);
        return ContractSecurityView.of(created);
    }

    private Contract requireContract(UUID tenantId, UUID contractId) {
        return contracts.findById(tenantId, contractId)
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
