package com.kmarket.ordersservice.client;

import com.kmarket.ordersservice.entity.OrderItem;

import java.util.List;

/**
 * Reserves stock for an order in the Stock (inventory) service — step 2 of the Saga.
 *
 * <p>The reservation is all-or-nothing and idempotent by {@code order_id} (safe to retry),
 * as specified by {@code POST /stock/reserve}. The call is made with the orders
 * {@code service_account} identity, not the end user's token.</p>
 */
public interface StockClient {

    /**
     * Attempts to reserve the given order lines.
     *
     * @param orderId the persisted order id (reservation key; makes retries idempotent)
     * @param lines   the order's lines (product id + quantity)
     * @return the outcome: reserved, insufficient stock, or uncertain (timeout/transport)
     */
    StockReservationOutcome reserve(int orderId, List<OrderItem> lines);
}
