package com.kmarket.paymentservice;

import com.kmarket.paymentservice.dto.PaymentCreateRequest;
import com.kmarket.paymentservice.dto.PaymentResponse;
import com.kmarket.paymentservice.exception.ApiException;
import com.kmarket.paymentservice.repository.PaymentRepository;
import com.kmarket.paymentservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that concurrent duplicate capture requests sharing one {@code Idempotency-Key}
 * cannot double-charge: the {@code UNIQUE (idempotency_key)} constraint in PostgreSQL lets
 * exactly one insert win, so only a single {@code payments} row is ever created. Losers
 * either replay the winner's result or get a {@code 409}, never a second charge.
 *
 * <p>Runs against a real (Testcontainers) PostgreSQL; the race is only meaningful against
 * a real database enforcing the unique constraint.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentConcurrencyIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;

    private int orderId;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderId = 5_000;
    }

    @Test
    void concurrentDuplicateCaptures_createExactlyOnePayment() throws Exception {
        String key = UUID.randomUUID().toString();
        PaymentCreateRequest request = new PaymentCreateRequest(orderId, 4990, "EUR");

        CyclicBarrier startLine = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        try {
            List<Callable<PaymentResponse>> tasks = java.util.Collections.nCopies(THREADS, (Callable<PaymentResponse>) () -> {
                // Release all threads at the same instant to maximise contention.
                startLine.await();
                try {
                    PaymentResponse r = paymentService.capture(key, request);
                    succeeded.incrementAndGet();
                    return r;
                } catch (ApiException ex) {
                    // The loser of the UNIQUE race sees a 409 CONFLICT - never a double charge.
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    conflicts.incrementAndGet();
                    return null;
                }
            });

            List<Future<PaymentResponse>> futures = pool.invokeAll(tasks);
            for (Future<PaymentResponse> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // The database enforced single-charge: exactly one row for the key/order.
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(paymentRepository.findByIdempotencyKey(key)).isPresent();
        assertThat(paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId)).isPresent();

        // Every caller got a definite answer (either the payment or a conflict).
        assertThat(succeeded.get() + conflicts.get()).isEqualTo(THREADS);
        // At least one caller observed the created/replayed payment.
        assertThat(succeeded.get()).isGreaterThanOrEqualTo(1);
    }
}
