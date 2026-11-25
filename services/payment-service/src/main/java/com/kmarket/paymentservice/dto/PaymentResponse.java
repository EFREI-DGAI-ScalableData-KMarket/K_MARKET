package com.kmarket.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kmarket.paymentservice.entity.Payment;

import java.time.OffsetDateTime;

/**
 * Response matching the {@code Payment} schema of the contract.
 *
 * <p>{@code currency} is always {@code EUR} (the only contract-allowed value; the table
 * has no currency column). {@code updated_at} is part of the schema but the table has no
 * such column, so it is serialized only when known (currently {@code null}) — the schema
 * marks no {@code Payment} field as required, so omitting it is contract-compatible.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        Integer id,
        @JsonProperty("order_id") Integer orderId,
        @JsonProperty("amount_cents") Integer amountCents,
        String currency,
        String status,
        @JsonProperty("failure_reason") String failureReason,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmountCents(),
                PaymentCreateRequest.DEFAULT_CURRENCY,
                payment.getStatus(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                null);
    }
}
