package com.kmarket.ordersservice.client;

/**
 * Captures and reads payments in the Payment service — step 3 of the Saga.
 *
 * <p>The capture is idempotent on an {@code Idempotency-Key} that is <b>stable per order</b>
 * (see {@link #paymentKey(int)}): replaying the same key after a lost response returns the
 * original outcome instead of charging again, as mandated by {@code payments.openapi.yaml}.
 * All calls use the orders {@code service_account} identity (decision D6), never the end
 * user's token.</p>
 */
public interface PaymentClient {

    /**
     * Captures a payment for an order ({@code POST /payments}).
     *
     * @param orderId        the persisted order id (the payment is tied to it)
     * @param amountCents    the order total in cents
     * @param idempotencyKey a UUID {@code Idempotency-Key}, stable for this order (use
     *                       {@link #paymentKey(int)}); a retry MUST reuse the same value
     * @return the outcome: succeeded, definitively declined, or uncertain (timeout/transport)
     */
    PaymentOutcome capture(int orderId, long amountCents, String idempotencyKey);

    /**
     * Reads the current payment of an order ({@code GET /payments/{order_id}}), used to
     * recover the real outcome after an uncertain capture (lost response / timeout) without
     * initiating a second payment.
     *
     * @param orderId the order id
     * @return the recovered outcome: {@link PaymentOutcome#SUCCEEDED} if a succeeded payment
     *         exists, {@link PaymentOutcome#DECLINED} if it failed, or
     *         {@link PaymentOutcome#UNCERTAIN} if still unknown (no payment yet / lookup error)
     */
    PaymentOutcome lookup(int orderId);

    /**
     * Refunds an order's payment ({@code POST /payments/{order_id}/refund}) — the explicit
     * compensation when a payment succeeded but the order can no longer be fulfilled (e.g.
     * the stock reservation is definitively gone). Idempotent/tolerant: the Payment service
     * answers {@code 409} if the payment is already refunded or was never {@code succeeded},
     * which is treated as "already compensated". Implementations must not throw on a
     * best-effort refund failure path beyond what the caller can reconcile.
     *
     * @param orderId the order id whose payment should be refunded
     * @return {@code true} if the payment is now refunded (or already was), {@code false} if
     *         the outcome is uncertain and needs reconciliation
     */
    boolean refund(int orderId);

    /**
     * Derives the deterministic, restart-safe {@code Idempotency-Key} for an order's payment.
     * Derived purely from the persisted {@code orderId}, so it is identical across retries
     * and process restarts without storing any extra state, and it is a valid UUID as the
     * contract requires.
     *
     * @param orderId the persisted order id
     * @return a stable UUID string for this order's single logical payment
     */
    static String paymentKey(int orderId) {
        return java.util.UUID.nameUUIDFromBytes(
                ("order-payment-" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
