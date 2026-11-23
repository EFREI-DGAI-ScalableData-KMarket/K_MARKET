package com.kmarket.ordersservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Request body matching the {@code OrderCreate} schema of the contract. */
public record OrderCreateRequest(
        @NotNull @JsonProperty("customer_id") Integer customerId,
        @NotEmpty @Valid List<Item> items) {

    /** A single requested line: a product and a (positive) quantity. */
    public record Item(
            @NotNull @JsonProperty("product_id") Integer productId,
            @NotNull @Min(1) Integer quantity) {
    }
}
