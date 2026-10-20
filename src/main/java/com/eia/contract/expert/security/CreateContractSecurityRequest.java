package com.eia.contract.expert.security;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/contracts/{contractId}/security}.
 *
 * <p>The status and the record identity are assigned by the expert, not by the
 * caller. {@code approvedAt} is accepted as optional approval evidence for an
 * over-collateralised record; the public collateral control only requires the
 * approval reference.</p>
 */
public record CreateContractSecurityRequest(
        String securityType,
        BigDecimal requiredAmount,
        BigDecimal heldAmount,
        String currency,
        UUID providerPartyId,
        LocalDate expiryDate,
        String approvalRef,
        Instant approvedAt
) {}
