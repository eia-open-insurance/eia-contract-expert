package com.eia.contract.expert.technicalaccount;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a technical account (SPEC 11.3.4 {@code technical_account_line}).
 *
 * <p>A line is a child of the technical account. It carries an optional
 * reference and description for audit, a strictly positive amount, and one
 * {@link TechnicalAccountLineType}. Each type rolls into one of the four header
 * components; lines can only be added or removed while the account is
 * {@code DRAFT}. They are the detail behind the header amounts and their bucket
 * totals must reconcile to the header before issue.</p>
 *
 * <p>Money is stored as {@code numeric(19,4)} and exposed as a decimal string
 * (SPEC 10.3).</p>
 */
public record ContractTechnicalAccountLine(
        UUID id,
        UUID tenantId,
        UUID accountId,
        TechnicalAccountLineType lineType,
        String reference,
        String description,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        Instant createdAt
) {

    public static ContractTechnicalAccountLine create(
            UUID tenantId,
            UUID accountId,
            TechnicalAccountLineType lineType,
            String reference,
            String description,
            BigDecimal amount) {
        return new ContractTechnicalAccountLine(
                UUID.randomUUID(), tenantId, accountId, lineType, reference, description,
                amount, Instant.now());
    }
}
