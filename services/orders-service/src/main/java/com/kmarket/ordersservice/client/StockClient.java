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

    /**
     * Confirms (commits) a paid order's reservations ({@code POST /stock/commit}) — the Saga
     * finalization step after a successful payment. Idempotent: an order whose reservations
     * are already {@code committed} is confirmed again without error.
     *
     * @param orderId the persisted order id
     * @return {@link StockOperationOutcome#DONE} on success, {@link StockOperationOutcome#NOT_FOUND}
     *         if no reservation exists, or {@link StockOperationOutcome#UNCERTAIN} on
     *         timeout/transport/ambiguous status
     */
    StockOperationOutcome commit(int orderId);

    /**
     * Releases an order's reservations ({@code POST /stock/release}) — the Saga compensation
     * when payment is declined (or the order is cancelled). Idempotent: reservations already
     * {@code released} do not return stock twice.
     *
     * @param orderId the persisted order id
     * @return {@link StockOperationOutcome#DONE} on success, {@link StockOperationOutcome#NOT_FOUND}
     *         if nothing to release, or {@link StockOperationOutcome#UNCERTAIN} on
     *         timeout/transport/ambiguous status
     */
    StockOperationOutcome release(int orderId);
}
