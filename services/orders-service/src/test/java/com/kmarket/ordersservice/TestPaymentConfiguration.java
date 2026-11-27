package com.kmarket.ordersservice;

import com.kmarket.ordersservice.client.PaymentClient;
import com.kmarket.ordersservice.client.PaymentOutcome;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test double for the {@link PaymentClient} so {@code @SpringBootTest} Saga tests can
 * exercise the payment step without a live Payment service. The capture/lookup outcomes
 * are settable per test (default {@code SUCCEEDED}); capture/refund calls and the last
 * idempotency key are recorded so tests can assert idempotency and compensation.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestPaymentConfiguration {

    @Bean
    ControllablePaymentClient paymentClient() {
        return new ControllablePaymentClient();
    }

    /** A {@link PaymentClient} whose returned outcomes can be set from a test. */
    public static class ControllablePaymentClient implements PaymentClient {

        public final AtomicReference<PaymentOutcome> captureOutcome =
                new AtomicReference<>(PaymentOutcome.SUCCEEDED);
        public final AtomicReference<PaymentOutcome> lookupOutcome =
                new AtomicReference<>(PaymentOutcome.UNCERTAIN);
        public final AtomicReference<Boolean> refundResult = new AtomicReference<>(Boolean.TRUE);

        public final AtomicInteger captureCalls = new AtomicInteger();
        public final AtomicInteger lookupCalls = new AtomicInteger();
        public final AtomicInteger refundCalls = new AtomicInteger();
        public final AtomicReference<String> lastKey = new AtomicReference<>();

        @Override
        public PaymentOutcome capture(int orderId, long amountCents, String idempotencyKey) {
            captureCalls.incrementAndGet();
            lastKey.set(idempotencyKey);
            return captureOutcome.get();
        }

        @Override
        public PaymentOutcome lookup(int orderId) {
            lookupCalls.incrementAndGet();
            return lookupOutcome.get();
        }

        @Override
        public boolean refund(int orderId) {
            refundCalls.incrementAndGet();
            return refundResult.get();
        }
    }
}
