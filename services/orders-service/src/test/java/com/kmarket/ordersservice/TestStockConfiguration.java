package com.kmarket.ordersservice;

import com.kmarket.ordersservice.client.StockClient;
import com.kmarket.ordersservice.client.StockReservationOutcome;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Test double for the {@link StockClient} so {@code @SpringBootTest} order-creation tests
 * can exercise the Saga without a live Stock service. The outcome is settable per test via
 * {@link ControllableStockClient#outcome}; it defaults to {@code RESERVED} (happy path).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestStockConfiguration {

    @Bean
    ControllableStockClient stockClient() {
        return new ControllableStockClient();
    }

    /** A {@link StockClient} whose returned outcome can be set from a test. */
    public static class ControllableStockClient implements StockClient {

        public final AtomicReference<StockReservationOutcome> outcome =
                new AtomicReference<>(StockReservationOutcome.RESERVED);

        @Override
        public StockReservationOutcome reserve(int orderId, java.util.List<com.kmarket.ordersservice.entity.OrderItem> lines) {
            return outcome.get();
        }
    }
}
