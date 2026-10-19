package com.eia.contract.expert;

/**
 * A request violates a contract invariant. The expert is the final guard for data
 * integrity, even though the domain service owns business validation.
 */
public class ContractValidationException extends RuntimeException {
    private final String code;
    private final String field;

    public ContractValidationException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }
}
