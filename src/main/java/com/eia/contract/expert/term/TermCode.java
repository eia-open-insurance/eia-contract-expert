package com.eia.contract.expert.term;

/**
 * Canonical contract term codes and the type of value each one carries.
 *
 * <p>The codes cover the financial-term fields of SPEC 11.3.4 {@code contract_terms}
 * (commission, brokerage, funds withheld, deposit, minimum premium, corridor) plus
 * the contractual facts that the bind rule needs (settlement basis, reinstatement
 * provision, ultimate net loss, portfolio schedule, actuarial assumptions).</p>
 */
public enum TermCode {

    CEDING_COMMISSION_PCT(ValueType.PERCENT),
    BROKERAGE_PCT(ValueType.PERCENT),
    PROFIT_COMMISSION_PCT(ValueType.PERCENT),
    FUNDS_WITHHELD_PCT(ValueType.PERCENT),
    LOSS_CORRIDOR_FROM_PCT(ValueType.PERCENT),
    LOSS_CORRIDOR_TO_PCT(ValueType.PERCENT),
    EXPERIENCE_ACCOUNT_RATE_PCT(ValueType.PERCENT),
    LOSS_RATIO_CAP_PCT(ValueType.PERCENT),

    DEPOSIT_PREMIUM_AMOUNT(ValueType.MONEY),
    MINIMUM_PREMIUM_AMOUNT(ValueType.MONEY),
    CASH_CALL_LIMIT_AMOUNT(ValueType.MONEY),
    INCEPTION_BALANCE_AMOUNT(ValueType.MONEY),

    GOVERNING_LAW(ValueType.TEXT),
    SETTLEMENT_BASIS(ValueType.TEXT),
    REINSTATEMENT_PROVISION(ValueType.TEXT),
    ULTIMATE_NET_LOSS(ValueType.TEXT),
    PORTFOLIO_SCHEDULE_REF(ValueType.TEXT),
    ACTUARIAL_ASSUMPTIONS_REF(ValueType.TEXT),
    DEVELOPMENT_PATTERN_REF(ValueType.TEXT),
    CURRENCY_CLAUSE(ValueType.TEXT),
    TAX_RESPONSIBILITY(ValueType.TEXT),
    PROFIT_COMMISSION_BASIS(ValueType.TEXT),

    SUNSET_DATE(ValueType.DATE),
    COMMUTATION_WINDOW_MONTHS(ValueType.INTEGER),
    PAYMENT_TERMS_DAYS(ValueType.INTEGER),

    OFFSET_ALLOWED(ValueType.BOOLEAN),
    CASH_CALL_ALLOWED(ValueType.BOOLEAN),
    AUDIT_RIGHTS(ValueType.BOOLEAN),
    EXPERIENCE_ACCOUNT_FLAG(ValueType.BOOLEAN);

    private final ValueType valueType;

    TermCode(ValueType valueType) {
        this.valueType = valueType;
    }

    public ValueType valueType() {
        return valueType;
    }

    public enum ValueType {
        MONEY, PERCENT, TEXT, DATE, INTEGER, BOOLEAN
    }
}
