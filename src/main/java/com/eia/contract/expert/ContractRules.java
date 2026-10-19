package com.eia.contract.expert;

import java.time.LocalDate;
import java.util.Locale;

/** Invariant checks applied at the expert boundary before any row is written. */
final class ContractRules {

    private ContractRules() {
    }

    static ContractType parseContractType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ContractValidationException("INVALID_CONTRACT_TYPE", "contractType",
                    "Contract type is required");
        }
        try {
            return ContractType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ContractValidationException("INVALID_CONTRACT_TYPE", "contractType",
                    "Unknown contract type: " + raw);
        }
    }

    static ContractStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ContractValidationException("INVALID_STATUS", "status", "Contract status is required");
        }
        try {
            return ContractStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ContractValidationException("INVALID_STATUS", "status", "Unknown contract status: " + raw);
        }
    }

    /**
     * A contract is created as a draft or an already quoted offer. Later lifecycle
     * states are reached through explicit transitions, never through insert.
     */
    static ContractStatus resolveInitialStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return ContractStatus.DRAFT;
        }
        ContractStatus status = parseStatus(raw);
        if (status != ContractStatus.DRAFT && status != ContractStatus.QUOTED) {
            throw new ContractValidationException("INVALID_INITIAL_STATUS", "status",
                    "A contract can only be created as DRAFT or QUOTED, not " + status);
        }
        return status;
    }

    static void validateDateRange(LocalDate effectiveDate, LocalDate expirationDate) {
        if (effectiveDate != null && expirationDate != null && effectiveDate.isAfter(expirationDate)) {
            throw new ContractValidationException("INVALID_DATE_RANGE", "expirationDate",
                    "effectiveDate must be on or before expirationDate");
        }
    }

    static void validateTransition(ContractStatus current, ContractStatus target) {
        if (!current.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(current, target);
        }
    }
}
