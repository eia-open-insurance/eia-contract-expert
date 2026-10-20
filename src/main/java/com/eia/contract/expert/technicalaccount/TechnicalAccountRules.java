package com.eia.contract.expert.technicalaccount;

import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.Values;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;

/** Validation and derived values for the technical-account write API. */
final class TechnicalAccountRules {

    private TechnicalAccountRules() {
    }

    /** Normalized create fields plus the derived balance. */
    record PeriodAmounts(
            LocalDate periodStart,
            LocalDate periodEnd,
            String currency,
            BigDecimal cededPremium,
            BigDecimal cededLosses,
            BigDecimal commission,
            BigDecimal otherCharges,
            BigDecimal balance
    ) {}

    static PeriodAmounts validate(CreateTechnicalAccountRequest request) {
        if (request.periodStart() == null) {
            throw invalid("INVALID_PERIOD_START", "periodStart", "periodStart is required");
        }
        if (request.periodEnd() == null) {
            throw invalid("INVALID_PERIOD_END", "periodEnd", "periodEnd is required");
        }
        if (!request.periodStart().isBefore(request.periodEnd())) {
            throw invalid("INVALID_PERIOD", "periodEnd",
                    "periodStart must be before periodEnd");
        }

        String currency = Values.currency(request.currency(), "currency");
        BigDecimal cededPremium = Values.money(request.cededPremium(), "cededPremium", true);
        BigDecimal cededLosses = Values.money(request.cededLosses(), "cededLosses", true);
        BigDecimal commission = Values.money(request.commission(), "commission", true);
        BigDecimal otherCharges = Values.money(request.otherCharges(), "otherCharges", true);

        // Positive = due from the cedent to the reinsurer. Commission and ceded
        // losses are amounts the reinsurer pays away, so they reduce the balance.
        BigDecimal balance = cededPremium
                .subtract(commission)
                .subtract(cededLosses)
                .add(otherCharges)
                .setScale(4, RoundingMode.HALF_UP);

        return new PeriodAmounts(request.periodStart(), request.periodEnd(), currency,
                cededPremium, cededLosses, commission, otherCharges, balance);
    }

    static TechnicalAccountStatus currentStatus(String raw) {
        return TechnicalAccountStatus.valueOf(raw);
    }

    static TechnicalAccountStatus parseTargetStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("INVALID_STATUS", "status", "status is required");
        }
        TechnicalAccountStatus target;
        try {
            target = TechnicalAccountStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalid("INVALID_STATUS", "status", "Unknown technical-account status: " + raw);
        }
        if (target == TechnicalAccountStatus.DRAFT) {
            throw invalid("INVALID_STATUS", "status",
                    "Only ISSUED and SETTLED can be requested; DRAFT is the creation state");
        }
        return target;
    }

    static void validateTransition(TechnicalAccountStatus from, TechnicalAccountStatus target) {
        if (!from.canTransitionTo(target)) {
            throw new ContractConflictException("INVALID_STATUS_TRANSITION",
                    "Technical-account status cannot move from " + from + " to " + target,
                    "status", "Allowed transitions: DRAFT -> ISSUED -> SETTLED");
        }
    }

    private static ContractValidationException invalid(String code, String field, String message) {
        return new ContractValidationException(code, field, message);
    }
}
