package com.eia.contract.expert.participant;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A participation in the contract or in one layer (SPEC 11.3.4
 * {@code contract_participant}).
 *
 * <p>{@code sharePct} is expressed in points of 100 (SPEC 11.3.4: "Of 100") with
 * six decimal places. Only risk-bearing roles count towards the 100% bind rule
 * (SPEC CTR-01); {@code CEDENT} and {@code BROKER} do not.</p>
 */
public record ContractParticipant(
        UUID id,
        UUID tenantId,
        UUID contractId,
        UUID layerId,
        UUID partyId,
        String participantRole,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sharePct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal signedLinePct,
        Integer orderNumber,
        boolean leadFlag,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal capacityAmount,
        String securityRating,
        boolean collateralRequired,
        String signatureStatus,
        Instant signedAt,
        String authorizationRef,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    public static ContractParticipant create(UUID tenantId, UUID contractId, UUID layerId, UUID partyId,
                                             String participantRole, BigDecimal sharePct,
                                             BigDecimal signedLinePct, Integer orderNumber, boolean leadFlag,
                                             BigDecimal capacityAmount, String securityRating,
                                             boolean collateralRequired, String signatureStatus,
                                             Instant signedAt, String authorizationRef,
                                             LocalDate effectiveFrom, LocalDate effectiveTo, String status) {
        Instant now = Instant.now();
        return new ContractParticipant(UUID.randomUUID(), tenantId, contractId, layerId, partyId,
                participantRole, sharePct, signedLinePct, orderNumber, leadFlag, capacityAmount,
                securityRating, collateralRequired, signatureStatus, signedAt, authorizationRef,
                effectiveFrom, effectiveTo, status, now, now);
    }
}
