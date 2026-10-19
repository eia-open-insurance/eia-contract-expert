package com.eia.contract.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Assumed-reinsurance contract aggregate root.
 *
 * <p>Mirrors the core columns of the SPEC contract aggregate (SPEC §11.3.4). Dates
 * are stored as {@code effective_date} / {@code expiration_date}; the
 * specification calls the start date the inception date. The bind and commutation
 * timestamps were added by the second slice so the lifecycle evidence is durable.</p>
 */
public record Contract(
        UUID id,
        UUID tenantId,
        String contractNumber,
        String contractType,
        UUID cedentPartyId,
        UUID reinsurerPartyId,
        String status,
        LocalDate effectiveDate,
        LocalDate expirationDate,
        String currency,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant boundAt,
        UUID boundBy,
        Instant commutedAt,
        Instant closedAt
) {}
