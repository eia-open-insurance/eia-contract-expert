package com.eia.contract.expert.technicalaccount;

/**
 * Technical-account lifecycle.
 *
 * <p>The slice deliberately implements only the linear path required by the
 * brief: {@code DRAFT -> ISSUED -> SETTLED}. The SPEC also names {@code AGREED},
 * {@code DISPUTED}, and {@code SUPERSEDED}; those are not part of this API yet.
 * The ordinal plus one is the child-aggregate event version.</p>
 */
public enum TechnicalAccountStatus {
    DRAFT,
    ISSUED,
    SETTLED;

    public boolean canTransitionTo(TechnicalAccountStatus target) {
        return (this == DRAFT && target == ISSUED) || (this == ISSUED && target == SETTLED);
    }

    public int eventVersion() {
        return ordinal() + 1;
    }
}
