package com.kmarket.paymentservice;

import com.kmarket.paymentservice.dto.PaymentCreateRequest;
import com.kmarket.paymentservice.dto.PaymentResponse;
import com.kmarket.paymentservice.entity.Payment;
import com.kmarket.paymentservice.exception.ApiException;
import com.kmarket.paymentservice.exception.PaymentDeclinedException;
import com.kmarket.paymentservice.repository.PaymentRepository;
import com.kmarket.paymentservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the idempotent capture / lookup core of {@link PaymentService},
 * running against a real (Testcontainers) PostgreSQL seeded with the {@code payments}
 * schema. No locally installed database is required.
 *
 * <p>Covers: successful capture, over-limit decline persisted as {@code failed}, the three
 * idempotency outcomes (replay / conflict / declined-replay), lookup + not-found, and the
 * lost-response recovery scenario that lets Orders Service re-read a result safely.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentCaptureIntegrationTest {

    /** Unique order ids per test so a shared container stays isolated between methods. */
    private static final AtomicInteger ORDER_IDS = new AtomicInteger(1_000);

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;

    private int orderId;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderId = ORDER_IDS.incrementAndGet();
    }

    private static PaymentCreateRequest request(int orderId, int amountCents) {
        return new PaymentCreateRequest(orderId, amountCents, "EUR");
    }

    // ---------------------------------------------------------------------
    // Successful payment
    // ---------------------------------------------------------------------

    @Test
    void capture_validAmount_persistsSucceededPayment() {
        String key = UUID.randomUUID().toString();

        PaymentResponse result = paymentService.capture(key, request(orderId, 4990));

        assertThat(result.id()).isNotNull();
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.amountCents()).isEqualTo(4990);
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.failureReason()).isNull();

        Payment stored = paymentRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("succeeded");
        assertThat(stored.getFailureReason()).isNull();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Declined payment above 200 euros (persisted as 'failed')
    // ---------------------------------------------------------------------

    @Test
    void capture_amountOverLimit_declinesAndPersistsFailedRow() {
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> paymentService.capture(key, request(orderId, 20001)))
                .isInstanceOf(PaymentDeclinedException.class)
                .satisfies(ex -> {
                    PaymentDeclinedException declined = (PaymentDeclinedException) ex;
                    assertThat(declined.getAmountCents()).isEqualTo(20001);
                    assertThat(declined.getLimitCents()).isEqualTo(20000);
                });

        // The decline is committed in its own REQUIRES_NEW transaction, so the failed row
        // survives even though capture() ultimately threw: a retry replays the same outcome.
        Payment stored = paymentRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("failed");
        assertThat(stored.getFailureReason()).isEqualTo("amount_over_limit");
    }

    @Test
    void capture_exactlyAtLimit_succeeds() {
        // Only amounts strictly above the limit are declined; 200,00 euros is accepted.
        PaymentResponse result = paymentService.capture(UUID.randomUUID().toString(),
                request(orderId, 20000));

        assertThat(result.status()).isEqualTo("succeeded");
    }

    // ---------------------------------------------------------------------
    // Idempotency: same key + identical request -> replay (no re-charge)
    // ---------------------------------------------------------------------

    @Test
    void capture_sameKeyIdenticalRequest_replaysWithoutNewRow() {
        String key = UUID.randomUUID().toString();

        PaymentResponse first = paymentService.capture(key, request(orderId, 4990));
        PaymentResponse replay = paymentService.capture(key, request(orderId, 4990));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo("succeeded");
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Idempotency: same key + different request -> 409
    // ---------------------------------------------------------------------

    @Test
    void capture_sameKeyDifferentRequest_isConflict() {
        String key = UUID.randomUUID().toString();
        paymentService.capture(key, request(orderId, 4990));

        // Reusing the key with a different amount is a contract conflict.
        assertThatThrownBy(() -> paymentService.capture(key, request(orderId, 5990)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        // Reusing the key with a different order is also a conflict.
        assertThatThrownBy(() -> paymentService.capture(key, request(orderId + 1, 4990)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Idempotency: declined key replayed -> same 422 decline, not a 201
    // ---------------------------------------------------------------------

    @Test
    void capture_declinedKeyReplayed_replaysDecline() {
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> paymentService.capture(key, request(orderId, 25000)))
                .isInstanceOf(PaymentDeclinedException.class);

        // A retry with the same key must replay the decline, never charge the order.
        assertThatThrownBy(() -> paymentService.capture(key, request(orderId, 25000)))
                .isInstanceOf(PaymentDeclinedException.class);

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(paymentRepository.findByIdempotencyKey(key).orElseThrow().getStatus())
                .isEqualTo("failed");
    }

    // ---------------------------------------------------------------------
    // Retrieval + not-found
    // ---------------------------------------------------------------------

    @Test
    void getByOrderId_existingPayment_returnsIt() {
        String key = UUID.randomUUID().toString();
        PaymentResponse created = paymentService.capture(key, request(orderId, 4990));

        PaymentResponse found = paymentService.getByOrderId(orderId);

        assertThat(found.id()).isEqualTo(created.id());
        assertThat(found.orderId()).isEqualTo(orderId);
        assertThat(found.status()).isEqualTo("succeeded");
    }

    @Test
    void getByOrderId_unknownOrder_throwsNotFound() {
        assertThatThrownBy(() -> paymentService.getByOrderId(987_654))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---------------------------------------------------------------------
    // Payment result recovery after a simulated lost response
    // ---------------------------------------------------------------------

    @Test
    void capture_afterLostResponse_recoversSameResultWithoutDoubleCharge() {
        String key = UUID.randomUUID().toString();

        // 1. First capture succeeds and is committed in PostgreSQL...
        PaymentResponse first = paymentService.capture(key, request(orderId, 4990));
        // 2. ...but Orders Service never sees the HTTP response (timeout / lost packet).

        // 3a. Recovery path A: Orders retries POST with the SAME Idempotency-Key.
        //     The stored result is replayed - no second charge, same payment id.
        PaymentResponse retried = paymentService.capture(key, request(orderId, 4990));
        assertThat(retried.id()).isEqualTo(first.id());

        // 3b. Recovery path B: Orders reads GET /payments/{order_id} to recover the result.
        PaymentResponse recovered = paymentService.getByOrderId(orderId);
        assertThat(recovered.id()).isEqualTo(first.id());
        assertThat(recovered.status()).isEqualTo("succeeded");

        // Exactly one payment row exists for the order: the customer was charged only once.
        assertThat(paymentRepository.count()).isEqualTo(1);
    }
}
