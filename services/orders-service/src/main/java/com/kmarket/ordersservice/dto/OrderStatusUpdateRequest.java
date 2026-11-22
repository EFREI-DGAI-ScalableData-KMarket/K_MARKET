package com.kmarket.ordersservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body of {@code PATCH /orders/{order_id}/status}: the new {@code status}
 * (an {@code OrderStatus} contract value). The value is validated and mapped to the
 * domain enum in the service layer.
 */
public record OrderStatusUpdateRequest(
        @NotBlank String status) {
}
