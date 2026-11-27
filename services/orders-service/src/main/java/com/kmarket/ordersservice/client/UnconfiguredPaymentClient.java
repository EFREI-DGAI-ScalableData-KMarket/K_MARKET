package com.kmarket.ordersservice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PaymentClient} used when no Payment base URL is configured.
 *
 * <p>Returns {@link PaymentOutcome#UNCERTAIN} so an order is never silently marked paid nor
 * falsely declined when Payment is simply not wired: the order stays {@code stock_reserved}
 * and is surfaced as a recoverable/reconcilable outcome. This lets Orders Service start and
 * operate even when the Payment service is temporarily unavailable (payment-dependent
 * operations fail gracefully, never with a 500).</p>
 */
public class UnconfiguredPaymentClient implements PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(UnconfiguredPaymentClient.class);

    @Override
    public PaymentOutcome capture(int orderId, long amountCents, String idempotencyKey) {
        log.warn("Payment service is not configured (set PAYMENT_BASE_URL): cannot capture order {}", orderId);
        return PaymentOutcome.UNCERTAIN;
    }

    @Override
    public PaymentOutcome lookup(int orderId) {
        log.warn("Payment service is not configured (set PAYMENT_BASE_URL): cannot look up order {}", orderId);
        return PaymentOutcome.UNCERTAIN;
    }

    @Override
    public boolean refund(int orderId) {
        log.warn("Payment service is not configured (set PAYMENT_BASE_URL): cannot refund order {}", orderId);
        return false;
    }
}
