package com.kmarket.paymentservice.exception;

import com.kmarket.paymentservice.dto.ErrorResponse;
import com.kmarket.paymentservice.dto.PaymentFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/** Translates exceptions into the {@code Error} / {@code PaymentFailedError} schemas of the contract. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getCode(), ex.getMessage()));
    }

    /**
     * Declined payment (amount over the limit). A business outcome → {@code 422} with the
     * contract's {@code PaymentFailedError} body, never a {@code 500}.
     */
    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<PaymentFailedError> handleDeclined(PaymentDeclinedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(PaymentFailedError.amountExceedsLimit(ex.getAmountCents(), ex.getLimitCents()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("BAD_REQUEST", message.isBlank() ? "Invalid request" : message));
    }

    /** Wrong path/query-parameter type, e.g. {@code order_id=abc}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("BAD_REQUEST", "Invalid value for parameter '" + ex.getName() + "'"));
    }

    /** Missing required request header, e.g. {@code Idempotency-Key} on POST /payments. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("BAD_REQUEST", "Missing required header '" + ex.getHeaderName() + "'"));
    }

    /**
     * Database constraint violation that was not already turned into a domain error (e.g. the
     * {@code amount_cents >= 0} CHECK). Treated as unprocessable so the client sees a 4xx.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ErrorResponse.of("UNPROCESSABLE", "Request violates a data constraint"));
    }

    /** Catch-all for unforeseen exceptions. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "Unexpected error"));
    }
}
