package com.eia.contract.expert.technicalaccount;

import java.math.BigDecimal;

/**
 * Create payload for a technical-account line.
 *
 * <p>{@code lineType} is one of {@code PREMIUM}, {@code LOSS},
 * {@code COMMISSION}, or {@code OTHER} (case-insensitive). {@code reference}
 * and {@code description} are optional and trimmed; the amount is required and
 * must be strictly positive. The account id and tenant come from the path and
 * the request tenant, so they are not accepted in the body.</p>
 */
public record CreateTechnicalAccountLineRequest(
        String lineType,
        String reference,
        String description,
        BigDecimal amount
) {}
