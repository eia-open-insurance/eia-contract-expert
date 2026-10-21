package com.eia.contract.expert.technicalaccount;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read model for the technical-account line endpoint.
 *
 * <p>The lines are returned in insertion order. {@code totals} always carries
 * all four line types (zero when a type has no line), keyed by the type name,
 * and each value is a four-decimal money string (SPEC 10.3). The totals are the
 * values reconciled against the header on DRAFT -> ISSUED.</p>
 */
public record TechnicalAccountLineList(
        UUID accountId,
        List<ContractTechnicalAccountLine> lines,
        Map<String, String> totals
) {}
