
package com.kmarket.ordersservice.client;

import com.kmarket.ordersservice.entity.OrderItem;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.util.List;
import java.util.function.Supplier;

/**
 * Resilience4j decorator for Stock Saga calls.
 *
 * Uses:
 * - Bulkhead: limits concurrent Stock Service calls.
 * - Circuit Breaker: stops calls when Stock Service is failing.
 *
 * No automatic retries:
 * A timeout does not necessarily mean the operation failed.
 *
 * Uncertain results are handled by the Saga recovery mechanism.
 */
public class ResilientStockClient implements StockClient {

    private final StockClient delegate;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public ResilientStockClient(
            StockClient delegate,
            CircuitBreaker circuitBreaker,
            Bulkhead bulkhead) {

        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
    }

    @Override
    public StockReservationOutcome reserve(
            int orderId,
            List<OrderItem> lines) {

        return guard(
                () -> delegate.reserve(orderId, lines),
                StockReservationOutcome.UNCERTAIN
        );
    }

    @Override
    public StockOperationOutcome commit(int orderId) {
        return guard(
                () -> delegate.commit(orderId),
                StockOperationOutcome.UNCERTAIN
        );
    }

    @Override
    public StockOperationOutcome release(int orderId) {
        return guard(
                () -> delegate.release(orderId),
                StockOperationOutcome.UNCERTAIN
        );
    }

    private <T> T guard(
            Supplier<T> call,
            T whenUnavailable) {

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

            // Circuit OPEN or bulkhead full:
            // Do not assume the Stock operation failed.
            return whenUnavailable;
        }
    }
}
