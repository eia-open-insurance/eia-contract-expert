package com.eia.contract.expert.cashcall;

/**
 * Cash-call lifecycle.
 *
 * <p>A cash call is created {@code REQUESTED}. It is either issued
 * ({@code ISSUED}) and later settled ({@code SETTLED}), or cancelled while it is
 * still a request ({@code CANCELLED}). Settlement and cancellation are terminal,
 * so a later status request is a conflict rather than a silent no-op.</p>
 */
public enum CashCallStatus {
    REQUESTED,
    ISSUED,
    SETTLED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SETTLED || this == CANCELLED;
    }

    public boolean canTransitionTo(CashCallStatus target) {
        return (this == REQUESTED && (target == ISSUED || target == CANCELLED))
                || (this == ISSUED && target == SETTLED);
    }

    /**
     * Child-aggregate version: the request is the first fact; issuance and
     * cancellation are alternatives at version 2, and settlement is version 3.
     * A cancelled call can therefore never claim a version a settled call uses.
     */
    public int eventVersion() {
        return switch (this) {
            case REQUESTED -> 1;
            case ISSUED, CANCELLED -> 2;
            case SETTLED -> 3;
        };
    }
}
