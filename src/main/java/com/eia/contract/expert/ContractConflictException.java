package com.eia.contract.expert;

/**
 * A request is valid but conflicts with the stored aggregate state: an unbalanced
 * tower, a share total that is not 100%, a cession beyond capacity, an approval
 * that is missing, and so on. Mapped to HTTP 409.
 */
public class ContractConflictException extends RuntimeException {

    private final String code;
    private final String field;
    private final String detail;

    public ContractConflictException(String code, String message, String field, String detail) {
        super(message);
        this.code = code;
        this.field = field;
        this.detail = detail;
    }

    public ContractConflictException(String code, String message) {
        this(code, message, null, message);
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }

    public String detail() {
        return detail;
    }
}
