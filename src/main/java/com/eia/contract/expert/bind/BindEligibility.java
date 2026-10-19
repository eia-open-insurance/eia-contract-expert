package com.eia.contract.expert.bind;

import java.util.List;
import java.util.UUID;

/**
 * Result of evaluating the bind preconditions. {@code bindable} is true only when
 * every check passed; the UI shows the individual checks so a user can see what is
 * missing before asking for approval.
 */
public record BindEligibility(
        UUID contractId,
        String contractNumber,
        String status,
        boolean bindable,
        List<BindCheck> checks
) {}
