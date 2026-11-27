package com.kmarket.ordersservice.client;

/**
 * Result of attempting to reserve stock for an order.
 *
 * <ul>
 *   <li>{@link #RESERVED} — stock was reserved (or was already reserved for this order:
 *       {@code /stock/reserve} is idempotent by {@code order_id}).</li>
 *   <li>{@link #INSUFFICIENT} — at least one line could not be served; nothing was reserved.
 *       The order must be rejected without attempting payment.</li>
 *   <li>{@link #UNCERTAIN} — the call timed out or failed in a way where the reservation may
 *       or may not have been applied. This is NOT "insufficient"; the order is left pending
 *       for reconciliation and the caller surfaces a retryable error.</li>
 * </ul>
 */
public enum StockReservationOutcome {
    RESERVED,
    INSUFFICIENT,
    UNCERTAIN
}
