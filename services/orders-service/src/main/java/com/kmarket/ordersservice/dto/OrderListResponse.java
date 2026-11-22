package com.kmarket.ordersservice.dto;

import java.util.List;

/**
 * Paginated list of orders, matching the {@code 200} response of {@code GET /orders}
 * ({@code items} + {@code total}).
 */
public record OrderListResponse(
        List<OrderResponse> items,
        long total) {
}
