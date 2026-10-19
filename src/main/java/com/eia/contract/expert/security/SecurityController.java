package com.eia.contract.expert.security;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractGuard;
import com.eia.contract.expert.ContractNotFoundException;
import com.eia.contract.expert.ContractRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Internal security API (SPEC 11.3.10 security). */
@RestController
@RequestMapping("/internal/v1/contracts/{contractId}/security")
public class SecurityController {

    private final ContractRepository contracts;
    private final SecurityRepository securities;

    public SecurityController(ContractRepository contracts, SecurityRepository securities) {
        this.contracts = contracts;
        this.securities = securities;
    }

    @GetMapping
    public List<ContractSecurity> list(@RequestParam UUID tenantId, @PathVariable UUID contractId) {
        requireContract(tenantId, contractId);
        return securities.findAll(tenantId, contractId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContractSecurity create(@RequestParam UUID tenantId,
                                   @PathVariable UUID contractId,
                                   @RequestBody CreateSecurityRequest request) {
        Contract contract = requireContract(tenantId, contractId);
        ContractGuard.requireEditable(contract);
        return securities.create(SecurityRules.validate(contract, request));
    }

    private Contract requireContract(UUID tenantId, UUID contractId) {
        return contracts.findById(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    public record CreateSecurityRequest(
            String securityType,
            BigDecimal requiredAmount,
            BigDecimal heldAmount,
            String currency,
            UUID providerPartyId,
            UUID beneficiaryPartyId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            LocalDate expiryDate,
            String status,
            String collateralQuality,
            UUID documentId,
            String releaseConditions,
            String approvalRef,
            Instant approvedAt
    ) {}
}
