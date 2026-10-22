package com.eia.contract.expert.technicalaccount;

/**
 * Status payload for a technical account. Only {@code ISSUED} and
 * {@code SETTLED} are accepted; {@code DRAFT} is the creation state and arrives
 * only through POST.
 *
 * <p>{@code reference} is the settlement reference. It is required when the
 * target is {@code SETTLED}, trimmed, and capped at 120 characters;
 * {@code ISSUED} ignores it.</p>
 */
public record UpdateTechnicalAccountStatusRequest(String status, String reference) {

    /** Convenience for transitions that carry no settlement reference. */
    public UpdateTechnicalAccountStatusRequest(String status) {
        this(status, null);
    }
}
