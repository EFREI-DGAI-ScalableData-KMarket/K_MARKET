package com.kmarket.ordersservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kmarket.ordersservice.entity.Order;

import java.time.OffsetDateTime;

/** Response matching the {@code Order} schema of the contract. */
public record OrderResponse(
        Integer id,
        @JsonProperty("customer_id") Integer customerId,
        String status,
        @JsonProperty("total_cents") Integer totalCents,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalCents(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
