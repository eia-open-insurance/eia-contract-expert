package com.eia.contract.expert.security;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Public representation of a {@code contract_security} row.
 *
 * <p>The project kept the internal security record wide for the bind and
 * commutation checks. The contract-web Security tab only consumes the
 * collateral fields, so this view exposes exactly that contract and hides the
 * tenant, contract, effective-to, quality, document, and audit columns.</p>
 */
public record ContractSecurityView(
        UUID id,
        String securityType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal requiredAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal heldAmount,
        String currency,
        UUID providerPartyId,
        LocalDate expiryDate,
        String approvalRef,
        String status,
        Instant createdAt
) {

    public static ContractSecurityView of(ContractSecurity security) {
        return new ContractSecurityView(
                security.id(),
                security.securityType(),
                security.requiredAmount(),
                security.heldAmount(),
                security.currency(),
                security.providerPartyId(),
                security.expiryDate(),
                security.approvalRef(),
                security.status(),
                security.createdAt());
    }
}
