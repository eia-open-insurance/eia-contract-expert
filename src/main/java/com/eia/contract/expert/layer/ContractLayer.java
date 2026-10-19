package com.eia.contract.expert.layer;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One layer of the contract tower (SPEC 11.3.4 {@code contract_layer}).
 *
 * <p>Money is a decimal string on the wire (SPEC 10.3) and {@code numeric(19,4)} in
 * the database. {@code attachmentAmount} is the bottom of the band and
 * {@code limitAmount} its height, so the layer covers
 * {@code [attachment, attachment + limit)}.</p>
 */
public record ContractLayer(
        UUID id,
        UUID tenantId,
        UUID contractId,
        UUID sectionId,
        int layerNumber,
        String layerType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal attachmentAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal limitAmount,
        String currency,
        String limitBasis,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal aggregateLimitAmount,
        Integer reinstatementCount,
        String reinstatementType,
        String note,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    @JsonIgnore
    public BigDecimal top() {
        return attachmentAmount.add(limitAmount);
    }

    /** Capacity this layer contributes to the contract (SPEC CTR-04). */
    @JsonIgnore
    public BigDecimal capacity() {
        return aggregateLimitAmount == null ? limitAmount : aggregateLimitAmount;
    }

    public static ContractLayer create(UUID tenantId, UUID contractId, int layerNumber, String layerType,
                                       BigDecimal attachmentAmount, BigDecimal limitAmount, String currency,
                                       String limitBasis, BigDecimal aggregateLimitAmount,
                                       Integer reinstatementCount, String reinstatementType, String note,
                                       LocalDate effectiveFrom, LocalDate effectiveTo, String status) {
        Instant now = Instant.now();
        return new ContractLayer(UUID.randomUUID(), tenantId, contractId, null, layerNumber, layerType,
                attachmentAmount, limitAmount, currency, limitBasis, aggregateLimitAmount,
                reinstatementCount, reinstatementType, note, effectiveFrom, effectiveTo, status, now, now);
    }
}
