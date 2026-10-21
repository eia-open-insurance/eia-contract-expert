package com.eia.contract.expert.technicalaccount;

import com.eia.contract.expert.ApiError;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.Values;
import com.eia.contract.expert.technicalaccount.TechnicalAccountLineType.Bucket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Validation, totals, and issue-time reconciliation for technical-account lines.
 *
 * <p>The header remains the reconciled view: a line is detail that must add up
 * to the matching header component. The line types are the detailed audit
 * vocabulary; each one rolls into exactly one of the four header
 * {@link Bucket buckets}, and the bucket totals are compared with the header
 * when a DRAFT account is issued. The rule applies only when at least one line
 * exists, so accounts created before the line slice (or by the period
 * bordereau) can still be issued with only header amounts.</p>
 */
final class TechnicalAccountLineRules {

    static final int REFERENCE_MAX_LENGTH = 64;
    static final int DESCRIPTION_MAX_LENGTH = 255;

    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private TechnicalAccountLineRules() {
    }

    /** Normalized create fields. */
    record LineValues(
            TechnicalAccountLineType lineType,
            String reference,
            String description,
            BigDecimal amount
    ) {}

    static LineValues validate(CreateTechnicalAccountLineRequest request) {
        if (request == null) {
            throw invalid("INVALID_REQUEST", "lineType", "Request body is required");
        }
        TechnicalAccountLineType lineType = Values.enumOf(
                TechnicalAccountLineType.class, request.lineType(), "lineType");
        BigDecimal amount = Values.money(request.amount(), "amount", true);
        if (amount.signum() <= 0) {
            throw invalid("INVALID_AMOUNT", "amount", "amount must be greater than zero");
        }
        return new LineValues(lineType,
                optionalText(request.reference(), "reference", REFERENCE_MAX_LENGTH),
                optionalText(request.description(), "description", DESCRIPTION_MAX_LENGTH),
                amount);
    }

    /**
     * Lines are editable only while the account is still the working draft; an
     * issued account is the reconciled, immutable statement.
     */
    static void requireDraft(ContractTechnicalAccount account) {
        if (!TechnicalAccountStatus.DRAFT.name().equals(account.status())) {
            throw new ContractConflictException("ACCOUNT_NOT_EDITABLE",
                    "Technical account " + account.accountNumber() + " is " + account.status()
                            + "; lines can only be added or removed while it is DRAFT",
                    "status", "Only DRAFT technical accounts accept line changes");
        }
    }

    /** Detailed totals for the read model, including zeroes for absent types. */
    static Map<TechnicalAccountLineType, BigDecimal> sumLines(
            List<ContractTechnicalAccountLine> lines) {
        Map<TechnicalAccountLineType, BigDecimal> totals = emptyTotals();
        for (ContractTechnicalAccountLine line : lines) {
            totals.merge(line.lineType(), line.amount(), BigDecimal::add);
        }
        return totals;
    }

    /**
     * Rolls the detailed line-type totals into the four header buckets. The
     * bucket totals are the reconciliation view; the detailed totals remain the
     * audit breakdown returned beside them.
     */
    static Map<Bucket, BigDecimal> bucketTotals(
            Map<TechnicalAccountLineType, BigDecimal> totals) {
        Map<Bucket, BigDecimal> buckets = emptyBucketTotals();
        totals.forEach((type, amount) -> buckets.merge(type.bucket(), amount, BigDecimal::add));
        return buckets;
    }

    /** Detailed totals in wire order and as the decimal strings used everywhere else. */
    static Map<String, String> formattedTotals(
            Map<TechnicalAccountLineType, BigDecimal> totals) {
        Map<String, String> formatted = new LinkedHashMap<>();
        for (TechnicalAccountLineType type : TechnicalAccountLineType.values()) {
            formatted.put(type.name(), money(totals.getOrDefault(type, ZERO)));
        }
        return formatted;
    }

    /** Bucket totals keyed by their lowercase wire names and always all present. */
    static Map<String, String> formattedBucketTotals(Map<Bucket, BigDecimal> totals) {
        Map<String, String> formatted = new LinkedHashMap<>();
        for (Bucket bucket : Bucket.values()) {
            formatted.put(bucket.key(), money(totals.getOrDefault(bucket, ZERO)));
        }
        return formatted;
    }

    /**
     * Applies CTR-08 to the four header components. No lines means the legacy
     * header-only account, which stays issuable. A mismatch is reported for
     * every offending bucket in one conflict, so the caller can fix all of them
     * in one pass; the detail names the line types that roll into the bucket so
     * the caller can see what to correct.
     */
    static void reconcile(ContractTechnicalAccount account,
                          List<TechnicalAccountLineRepository.LineTotal> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<TechnicalAccountLineType, BigDecimal> actual = emptyTotals();
        for (TechnicalAccountLineRepository.LineTotal row : rows) {
            TechnicalAccountLineType type = TechnicalAccountLineType.valueOf(row.lineType());
            actual.merge(type, row.amount(), BigDecimal::add);
        }
        Map<Bucket, BigDecimal> actualBuckets = bucketTotals(actual);

        List<ApiError.FieldViolation> mismatches = new ArrayList<>();
        for (Bucket bucket : Bucket.values()) {
            BigDecimal expected = expectedTotal(account, bucket);
            BigDecimal lineTotal = actualBuckets.getOrDefault(bucket, ZERO);
            if (expected.compareTo(lineTotal) != 0) {
                mismatches.add(new ApiError.FieldViolation(bucket.name(),
                        bucketName(bucket) + " lines total " + money(lineTotal)
                                + " but " + bucket.headerField() + " is " + money(expected)));
            }
        }
        if (!mismatches.isEmpty()) {
            throw new TechnicalAccountLinesDoNotReconcileException(
                    account.accountNumber(), mismatches);
        }
    }

    private static Map<TechnicalAccountLineType, BigDecimal> emptyTotals() {
        Map<TechnicalAccountLineType, BigDecimal> totals =
                new EnumMap<>(TechnicalAccountLineType.class);
        for (TechnicalAccountLineType type : TechnicalAccountLineType.values()) {
            totals.put(type, ZERO);
        }
        return totals;
    }

    private static Map<Bucket, BigDecimal> emptyBucketTotals() {
        Map<Bucket, BigDecimal> totals = new EnumMap<>(Bucket.class);
        for (Bucket bucket : Bucket.values()) {
            totals.put(bucket, ZERO);
        }
        return totals;
    }

    private static BigDecimal expectedTotal(ContractTechnicalAccount account, Bucket bucket) {
        return switch (bucket) {
            case PREMIUM -> account.cededPremium();
            case LOSS -> account.cededLosses();
            case COMMISSION -> account.commission();
            case OTHER -> account.otherCharges();
        };
    }

    /** @return the contributing type names, e.g. {@code COMMISSION, BROKERAGE} */
    private static String bucketName(Bucket bucket) {
        return bucket.types().stream()
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }

    private static String optionalText(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw invalid("INVALID_" + field.toUpperCase(Locale.ROOT), field,
                    field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static ContractValidationException invalid(String code, String field, String message) {
        return new ContractValidationException(code, field, message);
    }
}
