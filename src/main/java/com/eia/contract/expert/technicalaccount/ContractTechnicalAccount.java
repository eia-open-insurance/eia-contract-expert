package com.eia.contract.expert.technicalaccount;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A periodic reinsurance technical account (SPEC 11.3.4 {@code technical_account}).
 *
 * <p><strong>Sign convention.</strong> The balance is derived when the account is
 * created:
 * {@code balance = cededPremium - commission - cededLosses + otherCharges}. A
 * positive balance is due from the cedent to the reinsurer; a negative balance is
 * due from the reinsurer to the cedent (for example when ceded losses and
 * commission exceed ceded premium). A balance of zero means nothing is due.</p>
 *
 * <p>Money is stored as {@code numeric(19,4)} and exposed as a decimal string
 * (SPEC 10.3). The unique period key and the status values are enforced by the
 * database as well as by the controller.</p>
 */
public record ContractTechnicalAccount(
        UUID id,
        UUID tenantId,
        UUID contractId,
        String accountNumber,
        LocalDate periodStart,
        LocalDate periodEnd,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededPremium,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededLosses,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal commission,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal otherCharges,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance,
        String status,
        Instant createdAt
) {

    public static ContractTechnicalAccount draft(
            UUID tenantId,
            UUID contractId,
            String accountNumber,
            LocalDate periodStart,
            LocalDate periodEnd,
            String currency,
            BigDecimal cededPremium,
            BigDecimal cededLosses,
            BigDecimal commission,
            BigDecimal otherCharges,
            BigDecimal balance) {
        return new ContractTechnicalAccount(
                UUID.randomUUID(), tenantId, contractId, accountNumber, periodStart, periodEnd,
                currency, cededPremium, cededLosses, commission, otherCharges, balance,
                TechnicalAccountStatus.DRAFT.name(), Instant.now());
    }
}
