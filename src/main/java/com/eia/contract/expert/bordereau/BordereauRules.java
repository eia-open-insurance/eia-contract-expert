package com.eia.contract.expert.bordereau;

import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.Values;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/** Validation for the period-bordereau write API. */
final class BordereauRules {

    static final int NOTES_MAX_LENGTH = 4000;

    private BordereauRules() {
    }

    /** Normalized create fields. */
    record BordereauValues(
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate receivedDate,
            String currency,
            BigDecimal premiumAmount,
            BigDecimal lossAmount,
            BigDecimal commissionAmount,
            BigDecimal otherChargesAmount,
            int lineCount,
            String notes
    ) {}

    static BordereauValues validate(CreateBordereauRequest request) {
        if (request.periodStart() == null) {
            throw invalid("INVALID_PERIOD_START", "periodStart", "periodStart is required");
        }
        if (request.periodEnd() == null) {
            throw invalid("INVALID_PERIOD_END", "periodEnd", "periodEnd is required");
        }
        if (!request.periodStart().isBefore(request.periodEnd())) {
            throw invalid("INVALID_PERIOD", "periodEnd", "periodStart must be before periodEnd");
        }
        if (request.receivedDate() == null) {
            throw invalid("INVALID_RECEIVED_DATE", "receivedDate", "receivedDate is required");
        }

        String currency = Values.currency(request.currency(), "currency");
        BigDecimal premium = Values.money(request.premiumAmount(), "premiumAmount", true);
        BigDecimal loss = Values.money(request.lossAmount(), "lossAmount", true);
        BigDecimal commission = Values.money(request.commissionAmount(), "commissionAmount", true);
        BigDecimal other = Values.money(request.otherChargesAmount(), "otherChargesAmount", true);
        Integer lineCount = Values.integer(request.lineCount(), "lineCount", 0, 10_000_000, true);
        String notes = notes(request.notes());

        return new BordereauValues(request.periodStart(), request.periodEnd(), request.receivedDate(),
                currency, premium, loss, commission, other, lineCount, notes);
    }

    static BordereauStatus currentStatus(String raw) {
        try {
            return BordereauStatus.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Unknown stored bordereau status: " + raw, ex);
        }
    }

    static BordereauStatus parseTargetStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("INVALID_STATUS", "status", "status is required");
        }
        BordereauStatus target;
        try {
            target = BordereauStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalid("INVALID_STATUS", "status", "Unknown bordereau status: " + raw);
        }
        if (target == BordereauStatus.RECEIVED) {
            throw invalid("INVALID_STATUS", "status",
                    "Only PROCESSED and FAILED can be requested; RECEIVED is the creation state");
        }
        return target;
    }

    static void validateTransition(BordereauStatus from, BordereauStatus target) {
        if (!from.canTransitionTo(target)) {
            throw new ContractConflictException("INVALID_STATUS_TRANSITION",
                    "Bordereau status cannot move from " + from + " to " + target,
                    "status", "Allowed transitions: RECEIVED -> PROCESSED or RECEIVED -> FAILED");
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
