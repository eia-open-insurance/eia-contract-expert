package com.eia.contract.expert.technicalaccount;

/**
 * Component of a technical-account header that a line contributes to.
 *
 * <p>The brief reduces the SPEC 11.3.4 line domain to the four header
 * components. Each line type maps to exactly one header amount, which is the
 * reconciliation rule applied when a DRAFT account is issued.</p>
 */
public enum TechnicalAccountLineType {
    PREMIUM("cededPremium"),
    LOSS("cededLosses"),
    COMMISSION("commission"),
    OTHER("otherCharges");

    private final String headerField;

    TechnicalAccountLineType(String headerField) {
        this.headerField = headerField;
    }

    /** @return the account header field this line type must reconcile to */
    public String headerField() {
        return headerField;
    }
}
