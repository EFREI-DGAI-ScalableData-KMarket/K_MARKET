package com.kmarket.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body matching the {@code PaymentCreate} schema of the contract.
 *
 * <p>{@code currency} defaults to {@code EUR} and the contract restricts it to that
 * single value. The {@code payments.payments} table has no {@code currency} column, so
 * the value is validated here but not persisted; responses always echo {@code EUR}
 * (see the service Javadoc for this documented contract/schema reconciliation).</p>
 */
public record PaymentCreateRequest(
        @NotNull @JsonProperty("order_id") Integer orderId,
        @NotNull @Min(1) @JsonProperty("amount_cents") Integer amountCents,
        @Pattern(regexp = "EUR", message = "currency must be EUR") String currency) {

    /** The only currency accepted by the contract. */
    public static final String DEFAULT_CURRENCY = "EUR";

    /** The effective currency, defaulting to {@code EUR} when the field is omitted. */
    public String effectiveCurrency() {
        return currency == null || currency.isBlank() ? DEFAULT_CURRENCY : currency;
    }
}
