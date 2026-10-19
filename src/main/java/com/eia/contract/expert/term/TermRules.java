package com.eia.contract.expert.term;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.Values;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Term validation: the code must be known, the value must match the code's type,
 * the effective range must be coherent, and the source clause must be one of the
 * SPEC 11.3.5 clause codes.
 */
public final class TermRules {

    /** SPEC 11.3.5 {@code ClauseCode}. */
    public static final Set<String> CLAUSE_CODES = Set.of(
            "FOLLOW_THE_FORTUNES", "FOLLOW_THE_SETTLEMENTS", "CLAIMS_CONTROL", "CLAIMS_COOPERATION",
            "CUT_THROUGH", "INSOLVENCY", "OFFSET", "CURRENCY", "TAXES", "SEVERAL_LIABILITY",
            "MATERIAL_CHANGE", "HOURS_CLAUSE", "NET_RETENTION", "ULTIMATE_NET_LOSS", "OCCURRENCE",
            "POLLUTION_EXCLUSION", "ASBESTOS_EXCLUSION", "SILICA_EXCLUSION", "PFAS_EXCLUSION",
            "OPIOID_EXCLUSION", "SUNSET", "COMMUTATION", "REINSTATEMENT", "LOSS_CORRIDOR",
            "AGGREGATE_EXTENSION", "RETROACTIVE_COVER", "LOSS_PORTFOLIO_TRANSFER",
            "ADVERSE_DEVELOPMENT", "FUNDS_WITHHELD", "INTEREST", "ARBITRATION", "GOVERNING_LAW",
            "SANCTIONS", "ANTI_BRIBERY", "CONFIDENTIALITY", "DATA_PROTECTION");

    private TermRules() {
    }

    public static ContractTerm validate(Contract contract, TermController.CreateTermRequest request) {
        TermCode code = Values.enumOf(TermCode.class, request.termCode(), "termCode");
        String rawValue = Values.text(request.termValue(), "termValue", 400);
        String valueType = code.valueType().name();
        if (request.valueType() != null && !request.valueType().isBlank()
                && !valueType.equalsIgnoreCase(request.valueType().trim())) {
            throw new ContractConflictException("TERM_VALUE_TYPE_MISMATCH",
                    "Term " + code + " carries a " + valueType + " value, not " + request.valueType(),
                    "valueType", "Omit valueType; it is derived from termCode");
        }
        String normalized = normalize(code, rawValue);
        LocalDate effectiveFrom = request.effectiveFrom() == null
                ? contract.effectiveDate() : request.effectiveFrom();
        LocalDate effectiveTo = request.effectiveTo();
        Values.dateRange(effectiveFrom, effectiveTo, "effective");
        String sourceClause = request.sourceClause() == null || request.sourceClause().isBlank()
                ? null
                : request.sourceClause().trim().toUpperCase(Locale.ROOT);
        if (sourceClause != null && !CLAUSE_CODES.contains(sourceClause)) {
            throw new ContractValidationException("INVALID_SOURCE_CLAUSE", "sourceClause",
                    "Unknown clause code: " + request.sourceClause());
        }
        return ContractTerm.create(contract.tenantId(), contract.id(), code.name(), normalized,
                valueType, effectiveFrom, effectiveTo, sourceClause);
    }

    private static String normalize(TermCode code, String raw) {
        try {
            return switch (code.valueType()) {
                case MONEY -> Values.money(new BigDecimal(raw), "termValue", true).toPlainString();
                case PERCENT -> Values.percent(new BigDecimal(raw), "termValue", true).toPlainString();
                case INTEGER -> String.valueOf(Values.integer(
                        Integer.parseInt(raw), "termValue", 0, 1_000_000, true));
                case DATE -> LocalDate.parse(raw).toString();
                case BOOLEAN -> normalizeBoolean(raw);
                case TEXT -> raw;
            };
        } catch (NumberFormatException | DateTimeParseException ex) {
            throw new ContractValidationException("INVALID_TERM_VALUE", "termValue",
                    "Term " + code + " requires a " + code.valueType() + " value, got " + raw);
        }
    }

    private static String normalizeBoolean(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("true") && !value.equals("false")) {
            throw new ContractValidationException("INVALID_TERM_VALUE", "termValue",
                    "Term requires a BOOLEAN value, got " + raw);
        }
        return value;
    }

    public static List<String> clauseCodes() {
        return CLAUSE_CODES.stream().sorted().toList();
    }
}
