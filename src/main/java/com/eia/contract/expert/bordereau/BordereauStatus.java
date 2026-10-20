package com.eia.contract.expert.bordereau;

/**
 * Period-bordereau lifecycle.
 *
 * <p>A bordereau is created {@code RECEIVED}. Processing either posts it to a
 * technical account ({@code PROCESSED}) or records the failure ({@code FAILED}).
 * Both terminal states are immutable, so a second status request is a conflict
 * rather than a silent no-op.</p>
 */
public enum BordereauStatus {
    RECEIVED,
    PROCESSED,
    FAILED;

    public boolean isTerminal() {
        return this != RECEIVED;
    }

    public boolean canTransitionTo(BordereauStatus target) {
        return this == RECEIVED && (target == PROCESSED || target == FAILED);
    }

    /**
     * Child-aggregate version: receipt is the first fact, and either terminal
     * fact is the second. The two terminal states cannot follow one another.
     */
    public int eventVersion() {
        return switch (this) {
            case RECEIVED -> 1;
            case PROCESSED, FAILED -> 2;
        };
    }
}
