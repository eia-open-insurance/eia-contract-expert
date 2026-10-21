package com.eia.contract.expert.technicalaccount;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read model for the technical-account line endpoint.
 *
 * <p>The lines are returned in insertion order. {@code totals} always carries
 * every detailed line type (zero when a type has no line), keyed by the type
 * name. {@code bucketTotals} always carries the four financial buckets keyed
 * {@code premium}, {@code loss}, {@code commission}, and {@code other}; these
 * are the values reconciled against the header on DRAFT -> ISSUED, while the
 * detailed totals remain the audit breakdown behind them. Both maps use the
 * four-decimal money strings of SPEC 10.3.</p>
 */
public record TechnicalAccountLineList(
        UUID accountId,
        List<ContractTechnicalAccountLine> lines,
        Map<String, String> totals,
        Map<String, String> bucketTotals
) {}
