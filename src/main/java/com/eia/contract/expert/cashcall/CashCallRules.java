package com.eia.contract.expert.cashcall;

import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.Values;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/** Validation for the cash-call write API. */
final class CashCallRules {

    static final int NOTES_MAX_LENGTH = 4000;

    private CashCallRules() {
    }

    /** Normalized create fields. */
    record CashCallValues(
            LocalDate periodStart,
            LocalDate periodEnd,
            BigDecimal amount,
            String currency,
            LocalDate dueDate,
            String notes
    ) {}

    static CashCallValues validate(CreateCashCallRequest request) {
        if (request.periodStart() == null) {
            throw invalid("INVALID_PERIOD_START", "periodStart", "periodStart is required");
        }
        if (request.periodEnd() == null) {
            throw invalid("INVALID_PERIOD_END", "periodEnd", "periodEnd is required");
        }
        if (!request.periodStart().isBefore(request.periodEnd())) {
            throw invalid("INVALID_PERIOD", "periodEnd", "periodStart must be before periodEnd");
        }

        // Values.money enforces the scale and rejects negatives; a cash call is a
        // forward payment, so zero is not a meaningful request either.
        BigDecimal amount = Values.money(request.amount(), "amount", true);
        if (amount.signum() == 0) {
            throw invalid("INVALID_AMOUNT", "amount", "amount must be greater than zero");
        }
        String currency = Values.currency(request.currency(), "currency");
        if (request.dueDate() == null) {
            throw invalid("INVALID_DUE_DATE", "dueDate", "dueDate is required");
        }

        return new CashCallValues(request.periodStart(), request.periodEnd(), amount, currency,
                request.dueDate(), notes(request.notes()));
    }

    static CashCallStatus currentStatus(String raw) {
        try {
            return CashCallStatus.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Unknown stored cash-call status: " + raw, ex);
        }
    }

    static CashCallStatus parseTargetStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("INVALID_STATUS", "status", "status is required");
        }
        CashCallStatus target;
        try {
            target = CashCallStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalid("INVALID_STATUS", "status", "Unknown cash-call status: " + raw);
        }
        if (target == CashCallStatus.REQUESTED) {
            throw invalid("INVALID_STATUS", "status",
                    "Only ISSUED, SETTLED, and CANCELLED can be requested; "
                            + "REQUESTED is the creation state");
        }
        return target;
    }

    static void validateTransition(CashCallStatus from, CashCallStatus target) {
        if (!from.canTransitionTo(target)) {
            throw new ContractConflictException("INVALID_STATUS_TRANSITION",
                    "Cash-call status cannot move from " + from + " to " + target,
                    "status", "Allowed transitions: REQUESTED -> ISSUED or REQUESTED -> CANCELLED; "
                    + "ISSUED -> SETTLED");
        }
    }

    static String notes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > NOTES_MAX_LENGTH) {
            throw invalid("INVALID_NOTES", "notes",
                    "notes must be at most " + NOTES_MAX_LENGTH + " characters");
        }
        return trimmed;
    }

    private static ContractValidationException invalid(String code, String field, String message) {
        return new ContractValidationException(code, field, message);
    }
}
