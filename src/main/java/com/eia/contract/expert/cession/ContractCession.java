package com.eia.contract.expert.cession;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A recorded cession (SPEC 11.3.4 {@code contract_cession}).
 *
 * <p>Contract is ASSUMED book only, so every cession carries
 * {@code sourceVertical = ASSUMED} and references cedent business keys
 * ({@code policyRef}, {@code claimRef}) rather than Policy or Direct Claims ids; the
 * book wall forbids Contract from holding direct-book identifiers.</p>
 */
public record ContractCession(
        UUID id,
        UUID tenantId,
        UUID contractId,
        UUID layerId,
        String cessionNumber,
        String sourceVertical,
        String sourceType,
        String policyRef,
        String claimRef,
        String cessionType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cessionPct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededPremiumAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededLossAmount,
        String currency,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Integer treatyYear,
        String status,
        UUID bordereauId,
        Instant recordedAt,
        int version,
        Instant createdAt,
        Instant updatedAt
) {

    public static ContractCession create(UUID tenantId, UUID contractId, UUID layerId, String cessionNumber,
                                         String sourceVertical, String sourceType, String policyRef,
                                         String claimRef, String cessionType, BigDecimal cessionPct,
                                         BigDecimal cededPremium, BigDecimal cededLoss, String currency,
                                         LocalDate effectiveFrom, LocalDate effectiveTo, Integer treatyYear,
                                         String status, UUID bordereauId) {
        Instant now = Instant.now();
        return new ContractCession(UUID.randomUUID(), tenantId, contractId, layerId, cessionNumber,
                sourceVertical, sourceType, policyRef, claimRef, cessionType, cessionPct, cededPremium,
                cededLoss, currency, effectiveFrom, effectiveTo, treatyYear, status, bordereauId,
                now, 0, now, now);
    }

    @JsonIgnore
    public boolean isActive() {
        return "RECORDED".equals(status) || "CONFIRMED".equals(status);
    }
}
