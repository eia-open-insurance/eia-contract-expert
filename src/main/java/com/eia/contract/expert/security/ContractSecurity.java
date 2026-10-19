package com.eia.contract.expert.security;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Reinsurance security (SPEC 11.3.4 {@code contract_security}, SPEC 7.20.2).
 *
 * <p>{@code requiredAmount} is the secured obligation and {@code heldAmount} the
 * collateral actually held. Collateral above the obligation is only allowed with an
 * approval reference, which is the over-collateralisation control the SPEC requires
 * before a balance sheet exposure is accepted.</p>
 */
public record ContractSecurity(
        UUID id,
        UUID tenantId,
        UUID contractId,
        String securityType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal requiredAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal heldAmount,
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
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static ContractSecurity create(UUID tenantId, UUID contractId, String securityType,
                                          BigDecimal requiredAmount, BigDecimal heldAmount, String currency,
                                          UUID providerPartyId, UUID beneficiaryPartyId,
                                          LocalDate effectiveFrom, LocalDate effectiveTo, LocalDate expiryDate,
                                          String status, String collateralQuality, UUID documentId,
                                          String releaseConditions, String approvalRef, Instant approvedAt) {
        Instant now = Instant.now();
        return new ContractSecurity(UUID.randomUUID(), tenantId, contractId, securityType,
                requiredAmount, heldAmount, currency, providerPartyId, beneficiaryPartyId,
                effectiveFrom, effectiveTo, expiryDate, status, collateralQuality, documentId,
                releaseConditions, approvalRef, approvedAt, now, now);
    }
}
