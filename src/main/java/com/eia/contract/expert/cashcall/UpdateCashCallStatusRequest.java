package com.eia.contract.expert.cashcall;

/**
 * Status payload for a cash call. Only {@code ISSUED}, {@code SETTLED}, and
 * {@code CANCELLED} are accepted; {@code REQUESTED} is the creation state and
 * arrives only through POST.
 *
 * <p>{@code reference} is the settlement reference. It is required when the
 * target is {@code SETTLED}, trimmed, and capped at 120 characters; the other
 * target statuses ignore it.</p>
 */
public record UpdateCashCallStatusRequest(String status, String reference) {

    /** Convenience for transitions that carry no settlement reference. */
    public UpdateCashCallStatusRequest(String status) {
        this(status, null);
    }
}
