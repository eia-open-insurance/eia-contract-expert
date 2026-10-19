package com.eia.contract.expert;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/** Maps expert exceptions to the shared structured error envelope. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ContractNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ContractNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateContractNumberException.class)
    public ResponseEntity<ApiError> duplicate(DuplicateContractNumberException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("DUPLICATE_CONTRACT_NUMBER", ex.getMessage(),
                        "contractNumber", ex.contractNumber()));
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<ApiError> invalidTransition(InvalidStatusTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("INVALID_STATUS_TRANSITION", ex.getMessage(),
                        "allowedNextStatuses", ex.allowed().toString()));
    }

    @ExceptionHandler(ConcurrentContractUpdateException.class)
    public ResponseEntity<ApiError> concurrent(ConcurrentContractUpdateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("CONCURRENT_MODIFICATION", ex.getMessage()));
    }

    @ExceptionHandler(ContractValidationException.class)
    public ResponseEntity<ApiError> validation(ContractValidationException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ex.code(), ex.getMessage(), ex.field(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> beanValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldViolation> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", "Request validation failed", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("MALFORMED_REQUEST", "Request body could not be parsed"));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> badRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("DATA_CONFLICT", "The contract violates a database constraint"));
    }
}
