package com.kmarket.ordersservice;

import com.kmarket.ordersservice.client.PaymentClient;
import com.kmarket.ordersservice.client.PaymentOutcome;
import com.kmarket.ordersservice.client.StockOperationOutcome;
import com.kmarket.ordersservice.client.StockReservationOutcome;
import com.kmarket.ordersservice.dto.OrderCreateRequest;
import com.kmarket.ordersservice.dto.OrderResponse;
import com.kmarket.ordersservice.entity.Customer;
import com.kmarket.ordersservice.exception.ApiException;
import com.kmarket.ordersservice.repository.CustomerRepository;
import com.kmarket.ordersservice.repository.IdempotencyKeyRepository;
import com.kmarket.ordersservice.repository.OrderItemRepository;
import com.kmarket.ordersservice.repository.OrderRepository;
import com.kmarket.ordersservice.repository.OutboxEventRepository;
import com.kmarket.ordersservice.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the payment leg of the {@code POST /orders} Saga, against a real
 * (Testcontainers) PostgreSQL. Covers: payment success -> paid (+ stock commit); decline ->
 * stock released + payment_failed; timeout recovery; unknown outcome recoverability; failed
 * stock release not completing compensation; insufficient stock -> no payment; idempotent
 * replay not re-charging; stable idempotency key; and the outbox transitions.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestCatalogConfiguration.class,
        TestStockConfiguration.class, TestPaymentConfiguration.class})
@TestPropertySource(properties = "orders.outbox.relay.enabled=false")
class OrderPaymentSagaIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private TestStockConfiguration.ControllableStockClient stockClient;
    @Autowired
    private TestPaymentConfiguration.ControllablePaymentClient paymentClient;

    private Integer customerId;

    /** Unique, explicitly-assigned customer ids (identity model: customers.id == auth user id). */
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_CUSTOMER_ID =
            new java.util.concurrent.atomic.AtomicInteger(2_000);

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        customerRepository.deleteAll();

        stockClient.outcome.set(StockReservationOutcome.RESERVED);
        stockClient.commitOutcome.set(StockOperationOutcome.DONE);
        stockClient.releaseOutcome.set(StockOperationOutcome.DONE);
        stockClient.commitCalls.set(0);
        stockClient.releaseCalls.set(0);

        paymentClient.captureOutcome.set(PaymentOutcome.SUCCEEDED);
        paymentClient.lookupOutcome.set(PaymentOutcome.UNCERTAIN);
        paymentClient.refundResult.set(Boolean.TRUE);
        paymentClient.captureCalls.set(0);
        paymentClient.lookupCalls.set(0);
        paymentClient.refundCalls.set(0);
        paymentClient.lastKey.set(null);

        Customer customer = new Customer();
        // Identity model: customers.id == auth.users.id (JWT sub); assigned explicitly, not DB-generated.
        customer.setId(NEXT_CUSTOMER_ID.getAndIncrement());
        customer.setName("Alice");
        customer.setEmail("alice+" + UUID.randomUUID() + "@market.test");
        customerId = customerRepository.save(customer).getId();
    }

    private OrderCreateRequest oneItem() {
        return new OrderCreateRequest(customerId, List.of(new OrderCreateRequest.Item(101, 1)));
    }

    // ---------------------------------------------------------------------
    // Successful payment -> paid, stock committed
    // ---------------------------------------------------------------------

    @Test
    void paymentSucceeds_orderBecomesPaid_andStockCommitted() {
        OrderResponse created = orderService.createOrder(UUID.randomUUID().toString(), oneItem());

        assertThat(created.status()).isEqualTo("paid");
        assertThat(paymentClient.captureCalls.get()).isEqualTo(1);
        assertThat(stockClient.commitCalls.get()).isEqualTo(1);
        assertThat(stockClient.releaseCalls.get()).isZero();
        // placed + stock_reserved + paid
        assertThat(outboxEventRepository.count()).isEqualTo(3);
        assertThat(outboxEventRepository.findAll().stream()
                .filter(e -> e.getPayload().contains("\"paid\"")).count()).isEqualTo(1);

        // The idempotency key sent to Payment is the deterministic, order-derived UUID.
        assertThat(paymentClient.lastKey.get()).isEqualTo(PaymentClient.paymentKey(created.id()));
    }

    // ---------------------------------------------------------------------
    // Definitive decline -> stock released, order payment_failed (402)
    // ---------------------------------------------------------------------

    @Test
    void paymentDeclined_releasesStock_andMarksPaymentFailed() {
        paymentClient.captureOutcome.set(PaymentOutcome.DECLINED);
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.PAYMENT_REQUIRED));

        var order = orderRepository.findAll().get(0);
        assertThat(order.getStatus()).isEqualTo("payment_failed");
        // Compensation released the reservation exactly once; stock was never committed.
        assertThat(stockClient.releaseCalls.get()).isEqualTo(1);
        assertThat(stockClient.commitCalls.get()).isZero();
        // placed + stock_reserved + payment_failed
        assertThat(outboxEventRepository.count()).isEqualTo(3);
        assertThat(outboxEventRepository.findAll().stream()
                .filter(e -> e.getPayload().contains("payment_failed")).count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Payment timeout -> uncertain -> 502, order stays stock_reserved (recoverable)
    // ---------------------------------------------------------------------

    @Test
    void paymentUncertain_leavesOrderStockReserved_forReconciliation() {
        paymentClient.captureOutcome.set(PaymentOutcome.UNCERTAIN);
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));

        var order = orderRepository.findAll().get(0);
        // Not paid, not failed: recoverable state. Stock neither committed nor released.
        assertThat(order.getStatus()).isEqualTo("stock_reserved");
        assertThat(stockClient.commitCalls.get()).isZero();
        assertThat(stockClient.releaseCalls.get()).isZero();
        // placed + stock_reserved only
        assertThat(outboxEventRepository.count()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // Recovery: a replay after an uncertain capture recovers the real outcome
    // via lookup, without a second logical payment (restart-safe, DB-driven).
    // ---------------------------------------------------------------------

    @Test
    void replayAfterUncertainCapture_recoversSuccess_viaLookup_withoutDoubleCharge() {
        String key = UUID.randomUUID().toString();

        // 1. First attempt: capture is uncertain (lost response) -> 502, stays stock_reserved.
        paymentClient.captureOutcome.set(PaymentOutcome.UNCERTAIN);
        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class);
        int orderId = orderRepository.findAll().get(0).getId();
        int capturesAfterFirst = paymentClient.captureCalls.get();

        // 2. The real payment actually SUCCEEDED at the provider; a retry with the SAME key
        //    recovers it. Model that: capture replays as succeeded (same key), so the Saga
        //    completes. The key must be identical across attempts (no new random key).
        paymentClient.captureOutcome.set(PaymentOutcome.SUCCEEDED);
        OrderResponse resumed = orderService.createOrder(key, oneItem());

        assertThat(resumed.id()).isEqualTo(orderId);
        assertThat(resumed.status()).isEqualTo("paid");
        assertThat(paymentClient.lastKey.get()).isEqualTo(PaymentClient.paymentKey(orderId));
        // Exactly one order; the retry did not create a duplicate.
        assertThat(orderRepository.count()).isEqualTo(1);
        // Capture was invoked again on resume but always with the SAME stable key (idempotent
        // at the Payment service): never a new logical payment.
        assertThat(paymentClient.captureCalls.get()).isGreaterThan(capturesAfterFirst);
    }

    @Test
    void replayAfterUncertainCapture_whenStillUnknown_remainsRecoverable() {
        String key = UUID.randomUUID().toString();
        paymentClient.captureOutcome.set(PaymentOutcome.UNCERTAIN);

        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class);
        // A second attempt while still unknown must stay recoverable (502), never auto-fail.
        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(orderRepository.findAll().get(0).getStatus()).isEqualTo("stock_reserved");
        assertThat(stockClient.releaseCalls.get()).isZero();
    }

    // ---------------------------------------------------------------------
    // Failed stock release must NOT complete the compensation
    // ---------------------------------------------------------------------

    @Test
    void paymentDeclined_butStockReleaseUncertain_doesNotCompleteCompensation() {
        paymentClient.captureOutcome.set(PaymentOutcome.DECLINED);
        stockClient.releaseOutcome.set(StockOperationOutcome.UNCERTAIN);
        String key = UUID.randomUUID().toString();

        // Release is uncertain -> 502; the order must NOT be marked payment_failed yet.
        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));

        var order = orderRepository.findAll().get(0);
        assertThat(order.getStatus()).isEqualTo("stock_reserved");
        assertThat(order.getStatus()).isNotEqualTo("payment_failed");
        // Only placed + stock_reserved; no payment_failed transition happened.
        assertThat(outboxEventRepository.count()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // Paid but stock commit definitively fails -> refund + payment_failed
    // ---------------------------------------------------------------------

    @Test
    void paymentSucceeds_butStockCommitNotFound_refundsAndFails() {
        paymentClient.captureOutcome.set(PaymentOutcome.SUCCEEDED);
        stockClient.commitOutcome.set(StockOperationOutcome.NOT_FOUND);
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        var order = orderRepository.findAll().get(0);
        assertThat(order.getStatus()).isEqualTo("payment_failed");
        // The charge was refunded (explicit compensation), not silently dropped.
        assertThat(paymentClient.refundCalls.get()).isEqualTo(1);
    }

    @Test
    void paymentSucceeds_butStockCommitUncertain_staysStockReservedForRetry() {
        paymentClient.captureOutcome.set(PaymentOutcome.SUCCEEDED);
        stockClient.commitOutcome.set(StockOperationOutcome.UNCERTAIN);
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));

        var order = orderRepository.findAll().get(0);
        // Payment already succeeded: do NOT refund or fail; keep stock_reserved for an
        // idempotent commit retry.
        assertThat(order.getStatus()).isEqualTo("stock_reserved");
        assertThat(paymentClient.refundCalls.get()).isZero();
    }

    // ---------------------------------------------------------------------
    // Insufficient stock -> payment is never attempted
    // ---------------------------------------------------------------------

    @Test
    void replayOfPendingOrder_afterUncertainReservation_recoversViaIdempotentReserve() {
        // §5 recovery: first reservation is UNCERTAIN -> order stays 'pending' (502).
        stockClient.outcome.set(StockReservationOutcome.UNCERTAIN);
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
        int orderId = orderRepository.findAll().get(0).getId();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo("pending");

        // Stock is now reachable; a replay with the SAME key re-runs the idempotent reserve and
        // continues the Saga to completion — no duplicate order, no new identifier.
        stockClient.outcome.set(StockReservationOutcome.RESERVED);
        OrderResponse resumed = orderService.createOrder(key, oneItem());

        assertThat(resumed.id()).isEqualTo(orderId);
        assertThat(resumed.status()).isEqualTo("paid");
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void replayOfPendingOrder_whenReservationStillUncertain_staysPending() {
        stockClient.outcome.set(StockReservationOutcome.UNCERTAIN);
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class);
        // Replay while Stock is still unreachable: must stay pending (502), never auto-reject.
        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(orderRepository.findAll().get(0).getStatus()).isEqualTo("pending");
        assertThat(paymentClient.captureCalls.get()).isZero();
    }

    @Test
    void insufficientStock_neverCallsPayment() {
        stockClient.outcome.set(StockReservationOutcome.INSUFFICIENT);
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> orderService.createOrder(key, oneItem()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));

        assertThat(orderRepository.findAll().get(0).getStatus()).isEqualTo("rejected");
        assertThat(paymentClient.captureCalls.get()).isZero();
    }

    // ---------------------------------------------------------------------
    // Idempotent replay of a completed order does not re-pay
    // ---------------------------------------------------------------------

    @Test
    void replayOfPaidOrder_doesNotProcessPaymentAgain() {
        String key = UUID.randomUUID().toString();

        OrderResponse first = orderService.createOrder(key, oneItem());
        assertThat(first.status()).isEqualTo("paid");
        int capturesAfterFirst = paymentClient.captureCalls.get();

        OrderResponse replay = orderService.createOrder(key, oneItem());

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo("paid");
        // No second capture: the completed order is returned as-is.
        assertThat(paymentClient.captureCalls.get()).isEqualTo(capturesAfterFirst);
        assertThat(orderRepository.count()).isEqualTo(1);
        // No extra events beyond the first create's three.
        assertThat(outboxEventRepository.count()).isEqualTo(3);
    }
}
