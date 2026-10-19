package com.eia.contract.expert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * Input normalization and validation shared by the expert write endpoints.
 *
 * <p>Money follows SPEC 10.3: {@code numeric(19,4)} in the database and a decimal
 * string on the wire. Percentages are stored as points of 100 because SPEC 11.3.4
 * defines {@code share_pct} as "of 100", with six decimal places.</p>
 */
public final class Values {

    private static final int MONEY_SCALE = 4;
    private static final int PERCENT_SCALE = 6;

    private Values() {
    }

    public static BigDecimal money(BigDecimal raw, String field, boolean required) {
        if (raw == null) {
            if (required) {
                throw invalid(field, field + " is required");
            }
            return null;
        }
        if (raw.signum() < 0) {
            throw invalid(field, field + " must not be negative");
        }
        return raw.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal percent(BigDecimal raw, String field, boolean required) {
        if (raw == null) {
            if (required) {
                throw invalid(field, field + " is required");
            }
            return null;
        }
        if (raw.signum() < 0 || raw.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw invalid(field, field + " must be between 0 and 100");
        }
        return raw.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    public static String currency(String raw, String field) {
        if (raw == null || !raw.trim().matches("[A-Z]{3}")) {
            throw invalid(field, field + " must be a three-letter uppercase ISO currency code");
        }
        return raw.trim();
    }

    public static <E extends Enum<E>> E enumOf(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalid(field, field + " is required");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalid(field, "Unknown " + field + ": " + raw);
        }
    }

    /** {@code to} is exclusive (SPEC 10.2); a present end date must be after the start. */
    public static void dateRange(LocalDate from, LocalDate to, String field) {
        if (from == null) {
            throw invalid(field, field + "From is required");
        }
        if (to != null && !to.isAfter(from)) {
            throw new ContractValidationException("INVALID_DATE_RANGE", field + "To",
                    field + "To must be after " + field + "From");
        }
    }

    public static String text(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw invalid(field, field + " is required");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw invalid(field, field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    public static String optionalText(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw invalid("text", "value must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    public static Integer integer(Integer raw, String field, int min, int max, boolean required) {
        if (raw == null) {
            if (required) {
                throw invalid(field, field + " is required");
            }
            return null;
        }
        if (raw < min || raw > max) {
            throw invalid(field, field + " must be between " + min + " and " + max);
        }
        return raw;
    }

    public static UUID uuid(UUID raw, String field, boolean required) {
        if (raw == null && required) {
            throw invalid(field, field + " is required");
        }
        return raw;
    }

    public static boolean flag(Boolean raw, String field, boolean required) {
        if (raw == null) {
            if (required) {
                throw invalid(field, field + " is required");
            }
            return false;
        }
        return raw;
    }

    private static ContractValidationException invalid(String field, String message) {
        return new ContractValidationException("INVALID_" + snake(field), field, message);
    }

    private static String snake(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }
}
