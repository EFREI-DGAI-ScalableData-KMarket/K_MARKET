package com.kmarket.ordersservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response matching the {@code OrderDetail} schema of the contract: the order head
 * (same fields as {@link OrderResponse}) enriched with the customer and the fully
 * priced order lines returned by {@code GET /orders/{order_id}}.
 */
public record OrderDetailResponse(
        Integer id,
        @JsonProperty("customer_id") Integer customerId,
        String status,
        @JsonProperty("total_cents") Integer totalCents,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        Customer customer,
        List<Item> items) {

    /** The {@code customer} sub-object of {@code OrderDetail}. */
    public record Customer(
            Integer id,
            String name,
            String email) {
    }

    /** A single priced line of {@code OrderDetail.items}. */
    public record Item(
            @JsonProperty("product_id") Integer productId,
            @JsonProperty("product_name") String productName,
            Integer quantity,
            @JsonProperty("unit_price_cents") Integer unitPriceCents,
            @JsonProperty("line_total_cents") Integer lineTotalCents) {
    }
}
