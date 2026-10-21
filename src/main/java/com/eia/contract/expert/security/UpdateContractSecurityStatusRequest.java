package com.eia.contract.expert.security;

/**
 * Status payload for a security record.
 *
 * <p>Only {@code RELEASED} and {@code EXPIRED} are accepted. {@code ACTIVE},
 * {@code OUTSTANDING}, {@code PARTIAL}, and {@code REQUIRED} are the working
 * states a record can be moved out of; {@code BREACH} is recorded by the
 * collateral checks, not requested through this public action.</p>
 */
public record UpdateContractSecurityStatusRequest(String status) {}
