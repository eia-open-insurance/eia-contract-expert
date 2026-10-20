package com.eia.contract.expert.bordereau;

/**
 * Status payload for a period bordereau. Only {@code PROCESSED} and
 * {@code FAILED} can be requested; {@code RECEIVED} is the creation state and
 * arrives only through POST.
 *
 * @param notes optional note stored with the transition; when omitted the
 *              existing note is kept
 */
public record UpdateBordereauStatusRequest(String status, String notes) {}
