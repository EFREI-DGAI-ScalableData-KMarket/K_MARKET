package com.kmarket.paymentservice.domain;

/**
 * The payment lifecycle statuses of the contract ({@code PaymentStatus} schema),
 * matching the {@code CHECK} constraint on {@code payments.payments.status}.
 *
 * <p>Persisted as the lower-case string stored in {@code payments.payments.status}; use
 * {@link #value()} to render it on the wire / to the database.</p>
 */
public enum PaymentStatus {

    PENDING("pending"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    REFUNDED("refunded");

    private final String value;

    PaymentStatus(String value) {
        this.value = value;
    }

    /** The wire/DB representation (e.g. {@code "succeeded"}). */
    public String value() {
        return value;
    }
}
