package com.eia.contract.expert;

import java.util.List;

/**
 * Structured error body returned by the expert internal API.
 *
 * @param code    stable machine-readable error code
 * @param message human-readable explanation
 * @param details optional field-level or contextual details
 */
public record ApiError(String code, String message, List<FieldViolation> details) {

    public ApiError(String code, String message) {
        this(code, message, List.of());
    }

    public record FieldViolation(String field, String message) {}

    public static ApiError of(String code, String message, String field, String detail) {
        return new ApiError(code, message, List.of(new FieldViolation(field, detail)));
    }
}
