package com.kmarket.ordersservice.client;

/**
 * Result of a payment capture attempt against the Payment service
 * ({@code POST /payments}), mirroring {@link StockReservationOutcome}.
 *
 * <ul>
 *   <li>{@link #SUCCEEDED} — the payment is {@code succeeded} (HTTP 201 on first capture,
 *       or the idempotent replay of a previously succeeded capture). Safe to finalize the
 *       Saga and move the order to {@code paid}.</li>
 *   <li>{@link #DECLINED} — a definitive business decline (HTTP 422, e.g. amount over the
 *       200&nbsp;€ limit). The stock reservation must be compensated (released) and the
 *       order moved to {@code payment_failed}.</li>
 *   <li>{@link #UNCERTAIN} — the call timed out, the response was lost, or an ambiguous
 *       status was returned. The payment may or may not have been applied. This is NOT a
 *       decline: the order is left in a recoverable state and a reconciliation (via
 *       {@code GET /payments/{order_id}}) resolves it. Never retried as a new logical
 *       payment because the idempotency key is stable per order.</li>
 * </ul>
 */
public enum PaymentOutcome {
    SUCCEEDED,
    DECLINED,
    UNCERTAIN
}
