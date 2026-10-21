package com.eia.contract.expert.technicalaccount;

import java.util.Arrays;
import java.util.List;

/**
 * Kind of detail line recorded against a technical account (SPEC 11.3.4
 * {@code technical_account_line}).
 *
 * <p>The type is the audit vocabulary a caller records; the financial view of
 * the statement is a smaller set of header components. Every line type rolls
 * into exactly one {@link Bucket}, and the bucket total is what must equal the
 * matching account header amount when a DRAFT account is issued (CTR-08).</p>
 */
public enum TechnicalAccountLineType {
    PREMIUM(Bucket.PREMIUM),
    COMMISSION(Bucket.COMMISSION),
    BROKERAGE(Bucket.COMMISSION),
    PAID_LOSS(Bucket.LOSS),
    OUTSTANDING_LOSS(Bucket.LOSS),
    IBNR(Bucket.LOSS),
    ALAE(Bucket.LOSS),
    REINSTATEMENT(Bucket.LOSS),
    CASH_CALL(Bucket.LOSS),
    FUNDS_WITHHELD(Bucket.OTHER),
    TAX(Bucket.OTHER),
    ADJUSTMENT(Bucket.OTHER),
    OTHER(Bucket.OTHER);

    private final Bucket bucket;

    TechnicalAccountLineType(Bucket bucket) {
        this.bucket = bucket;
    }

    /** @return the financial bucket this line type contributes to */
    public Bucket bucket() {
        return bucket;
    }

    /** @return the account header field this line type's bucket reconciles to */
    public String headerField() {
        return bucket.headerField();
    }

    /**
     * One of the four financial components of the technical-account header.
     *
     * <p>{@link #key()} is the lowercase name used by the {@code bucketTotals}
     * read model; {@link #headerField()} is the account column the total must
     * equal on issue.</p>
     */
    public enum Bucket {
        PREMIUM("premium", "cededPremium"),
        LOSS("loss", "cededLosses"),
        COMMISSION("commission", "commission"),
        OTHER("other", "otherCharges");

        private final String key;
        private final String headerField;

        Bucket(String key, String headerField) {
            this.key = key;
            this.headerField = headerField;
        }

        /** @return the wire key used for this bucket's total */
        public String key() {
            return key;
        }

        /** @return the account header field this bucket must reconcile to */
        public String headerField() {
            return headerField;
        }

        /**
         * @return the line types that contribute to this bucket, in the order
         *         they are declared on {@link TechnicalAccountLineType}
         */
        public List<TechnicalAccountLineType> types() {
            return Arrays.stream(TechnicalAccountLineType.values())
                    .filter(type -> type.bucket == this)
                    .toList();
        }
    }
}
