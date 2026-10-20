package com.eia.contract.expert.bordereau;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A period bordereau for one contract period.
 *
 * <p>A bordereau arrives {@code RECEIVED} with the cedent's declared premium,
 * loss, commission, and other-charge totals. Processing it either creates (or
 * reuses) the technical account for the same period and links it through
 * {@link #technicalAccountId()}, or records that the bordereau could not be
 * processed ({@code FAILED}). {@code PROCESSED} and {@code FAILED} are terminal.</p>
 *
 * <p>Money is {@code numeric(19,4)} and exposed as a decimal string (SPEC 10.3).
 * The table is shared with the legacy ingest header ({@link ContractBordereauHeader});
 * a period bordereau is the row shape with {@code period_start} set, and the
 * unique key {@code (tenant_id, contract_id, period_start, period_end)} keeps one
 * financial bordereau per period.</p>
 */
public record ContractBordereau(
        UUID id,
        UUID tenantId,
        UUID contractId,
        String bordereauNumber,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate receivedDate,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal premiumAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lossAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal commissionAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal otherChargesAmount,
        int lineCount,
        String notes,
        String status,
        UUID technicalAccountId,
        Instant createdAt
) {

    public static ContractBordereau received(
            UUID tenantId,
            UUID contractId,
            String bordereauNumber,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate receivedDate,
            String currency,
            BigDecimal premiumAmount,
            BigDecimal lossAmount,
            BigDecimal commissionAmount,
            BigDecimal otherChargesAmount,
            int lineCount,
            String notes) {
        return new ContractBordereau(
                UUID.randomUUID(), tenantId, contractId, bordereauNumber, periodStart, periodEnd,
                receivedDate, currency, premiumAmount, lossAmount, commissionAmount,
                otherChargesAmount, lineCount, notes, BordereauStatus.RECEIVED.name(), null,
                Instant.now());
    }
}
