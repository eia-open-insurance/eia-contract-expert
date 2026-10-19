package com.eia.contract.expert.term;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One recorded contract term (SPEC 11.3.4 {@code contract_terms} as a key/value
 * registry; see IMPLEMENTATION.md for why the table is named {@code contract_term}).
 *
 * <p>The value is always a string. Money and percentage values are stored as
 * decimal strings so the precision survives the round trip (SPEC 10.3), and
 * {@code valueType} tells the API consumer how to render it.</p>
 */
public record ContractTerm(
        UUID id,
        UUID tenantId,
        UUID contractId,
        String termCode,
        String termValue,
        String valueType,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String sourceClause,
        Instant createdAt,
        Instant updatedAt
) {

    public static ContractTerm create(UUID tenantId, UUID contractId, String termCode, String termValue,
                                      String valueType, LocalDate effectiveFrom, LocalDate effectiveTo,
                                      String sourceClause) {
        Instant now = Instant.now();
        return new ContractTerm(UUID.randomUUID(), tenantId, contractId, termCode, termValue, valueType,
                effectiveFrom, effectiveTo, sourceClause, now, now);
    }
}
