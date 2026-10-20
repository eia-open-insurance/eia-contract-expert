package com.eia.contract.expert.cashcall;

/**
 * Status payload for a cash call. Only {@code ISSUED}, {@code SETTLED}, and
 * {@code CANCELLED} are accepted; {@code REQUESTED} is the creation state and
 * arrives only through POST.
 */
public record UpdateCashCallStatusRequest(String status) {}
