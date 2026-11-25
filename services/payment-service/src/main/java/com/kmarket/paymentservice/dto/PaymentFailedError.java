package com.kmarket.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Response matching the {@code PaymentFailedError} schema of the contract (HTTP 422):
 * the {@code Error} fields plus the decline {@code reason} and the amount/limit context.
 */
public record PaymentFailedError(
        String code,
        String message,
        @JsonProperty("request_id") String requestId,
        String reason,
        @JsonProperty("amount_cents") Integer amountCents,
        @JsonProperty("limit_cents") Integer limitCents) {

    /** The {@code amount_exceeds_limit} decline required by the {@code > 200 €} business rule. */
    public static PaymentFailedError amountExceedsLimit(int amountCents, int limitCents) {
        return new PaymentFailedError(
                "PAYMENT_FAILED",
                "Payment declined: amount exceeds the " + limitCents + " cents limit",
                UUID.randomUUID().toString(),
                "amount_exceeds_limit",
                amountCents,
                limitCents);
    }
}
