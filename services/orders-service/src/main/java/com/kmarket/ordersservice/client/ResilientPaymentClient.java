
package com.kmarket.ordersservice.client;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.util.function.Supplier;

/**
 * Resilience4j decorator for Payment Saga calls.
 *
 * Uses:
 * - Bulkhead: limits concurrent calls.
 * - Circuit Breaker: stops calls when Payment Service is failing.
 *
 * No automatic retry:
 * Payment mutations must not be blindly retried after a timeout.
 *
 * The underlying PaymentClient handles uncertain outcomes and
 * idempotency-based recovery.
 */
public class ResilientPaymentClient implements PaymentClient {

    private final PaymentClient delegate;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public ResilientPaymentClient(
            PaymentClient delegate,
            CircuitBreaker circuitBreaker,
            Bulkhead bulkhead) {

        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
    }

    @Override
    public PaymentOutcome capture(
            int orderId,
            long amountCents,
            String idempotencyKey) {

        return guard(
                () -> delegate.capture(orderId, amountCents, idempotencyKey),
                PaymentOutcome.UNCERTAIN
        );
    }

    @Override
    public PaymentOutcome lookup(int orderId) {
        return guard(
                () -> delegate.lookup(orderId),
                PaymentOutcome.UNCERTAIN
        );
    }

    @Override
    public boolean refund(int orderId) {
        return guard(
                () -> delegate.refund(orderId),
                false
        );
    }

    private <T> T guard(Supplier<T> call, T whenUnavailable) {

        try {
            Supplier<T> decorated =
                    Bulkhead.decorateSupplier(bulkhead, call);

            decorated = CircuitBreaker.decorateSupplier(
                    circuitBreaker,
                    decorated
            );

            return decorated.get();

        } catch (CallNotPermittedException |
                 BulkheadFullException ex) {

            return whenUnavailable;
        }
    }
}
