package com.eia.contract.expert.commutation;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A commutation (SPEC 11.3.4 {@code contract_commutation}, SPEC 7.21.1).
 *
 * <p>Lifecycle: {@code PROPOSED -> APPROVED -> EXECUTED}. Approval records the
 * approver and timestamp; execution requires a signed release document, releases the
 * contract's security, and moves the contract out of the in-force states.</p>
 */
public record ContractCommutation(
        UUID id,
        UUID tenantId,
        UUID contractId,
        String commutationNumber,
        String commutationType,
        LocalDate effectiveDate,
        LocalDate valuationDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal agreedAmount,
        String currency,
        String basis,
        String status,
        UUID releaseDocumentId,
        LocalDate releaseDate,
        UUID paymentId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal savingsAmount,
        UUID approvedBy,
        Instant approvedAt,
        Instant executedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static ContractCommutation create(UUID tenantId, UUID contractId, String commutationNumber,
                                             String commutationType, LocalDate effectiveDate,
                                             LocalDate valuationDate, BigDecimal agreedAmount,
                                             String currency, String basis, UUID releaseDocumentId,
                                             UUID paymentId, BigDecimal savingsAmount) {
        Instant now = Instant.now();
        return new ContractCommutation(UUID.randomUUID(), tenantId, contractId, commutationNumber,
                commutationType, effectiveDate, valuationDate, agreedAmount, currency, basis,
                "PROPOSED", releaseDocumentId, null, paymentId, savingsAmount, null, null, null,
                now, now);
    }
}
