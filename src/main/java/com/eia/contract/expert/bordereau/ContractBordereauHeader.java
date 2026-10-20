package com.eia.contract.expert.bordereau;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A bordereau received from the cedent (SPEC 11.3.4 {@code contract_bordereau}).
 *
 * <p>The header is what the legacy ingest slice stores: period, source, receipt,
 * status, line counts, declared total, and checksum. The period-bordereau
 * lifecycle is {@link ContractBordereau}; both share the table. Parsing lines
 * and posting them (SPEC 11.3.8 {@code contract.bordereau}) is still a gap.</p>
 */
public record ContractBordereauHeader(
        UUID id,
        UUID tenantId,
        UUID contractId,
        String bordereauNumber,
        String bordereauType,
        LocalDate periodFrom,
        LocalDate periodTo,
        Instant receivedAt,
        UUID sourceDocumentId,
        String sourceFormat,
        String sourceReference,
        String status,
        int lineCount,
        Integer validLineCount,
        Integer exceptionCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
        String currency,
        String checksum,
        Instant processedAt,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {

    public static ContractBordereauHeader create(UUID tenantId, UUID contractId,
                                                 String bordereauNumber, String bordereauType,
                                                 LocalDate periodFrom, LocalDate periodTo,
                                                 Instant receivedAt, UUID sourceDocumentId,
                                                 String sourceFormat, String sourceReference,
                                                 String status, int lineCount,
                                                 Integer validLineCount, Integer exceptionCount,
                                                 BigDecimal totalAmount, String currency,
                                                 String checksum, String notes) {
        Instant now = Instant.now();
        return new ContractBordereauHeader(UUID.randomUUID(), tenantId, contractId, bordereauNumber,
                bordereauType, periodFrom, periodTo, receivedAt, sourceDocumentId, sourceFormat,
                sourceReference, status, lineCount, validLineCount, exceptionCount, totalAmount,
                currency, checksum, null, notes, now, now);
    }
}
