package com.kmarket.paymentservice.exception;

import lombok.Getter;

/**
 * A declined payment — a <em>business outcome</em>, not an internal error. The payment is
 * persisted as {@code failed} with a {@code failure_reason}; the API answers {@code 422}
 * with a {@link com.kmarket.paymentservice.dto.PaymentFailedError} body (see the contract).
 */
@Getter
public class PaymentDeclinedException extends RuntimeException {

    private final int amountCents;
    private final int limitCents;

    public PaymentDeclinedException(String message, int amountCents, int limitCents) {
        super(message);
        this.amountCents = amountCents;
        this.limitCents = limitCents;
    }
}
