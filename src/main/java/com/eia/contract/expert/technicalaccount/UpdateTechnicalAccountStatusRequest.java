package com.eia.contract.expert.technicalaccount;

/**
 * Status payload for a technical account. Only {@code ISSUED} and
 * {@code SETTLED} are accepted; {@code DRAFT} is the creation state and arrives
 * only through POST.
 */
public record UpdateTechnicalAccountStatusRequest(String status) {}
