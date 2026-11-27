package com.kmarket.ordersservice;

import com.kmarket.ordersservice.client.StockClient;
import com.kmarket.ordersservice.client.StockOperationOutcome;
import com.kmarket.ordersservice.client.StockReservationOutcome;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test double for the {@link StockClient} so {@code @SpringBootTest} order-creation tests
 * can exercise the Saga without a live Stock service. The reserve/commit/release outcomes
 * are settable per test; they default to the happy path ({@code RESERVED} / {@code DONE}).
 * Commit and release calls are also counted so tests can assert compensation happened.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestStockConfiguration {

    @Bean
    ControllableStockClient stockClient() {
        return new ControllableStockClient();
    }

    /** A {@link StockClient} whose returned outcomes can be set from a test. */
    public static class ControllableStockClient implements StockClient {

        public final AtomicReference<StockReservationOutcome> outcome =
                new AtomicReference<>(StockReservationOutcome.RESERVED);
        public final AtomicReference<StockOperationOutcome> commitOutcome =
                new AtomicReference<>(StockOperationOutcome.DONE);
        public final AtomicReference<StockOperationOutcome> releaseOutcome =
                new AtomicReference<>(StockOperationOutcome.DONE);

        public final AtomicInteger commitCalls = new AtomicInteger();
        public final AtomicInteger releaseCalls = new AtomicInteger();

        @Override
        public StockReservationOutcome reserve(int orderId, java.util.List<com.kmarket.ordersservice.entity.OrderItem> lines) {
            return outcome.get();
        }

        @Override
        public StockOperationOutcome commit(int orderId) {
            commitCalls.incrementAndGet();
            return commitOutcome.get();
        }

        @Override
        public StockOperationOutcome release(int orderId) {
            releaseCalls.incrementAndGet();
            return releaseOutcome.get();
        }
    }
}

