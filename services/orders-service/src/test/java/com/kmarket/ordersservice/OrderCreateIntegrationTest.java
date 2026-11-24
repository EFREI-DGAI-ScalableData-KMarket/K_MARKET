package com.kmarket.ordersservice;

import com.kmarket.ordersservice.dto.OrderCreateRequest;
import com.kmarket.ordersservice.dto.OrderResponse;
import com.kmarket.ordersservice.entity.Customer;
import com.kmarket.ordersservice.event.OrderEvent;
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
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the transactional {@code POST /orders} core, against a real
 * (Testcontainers) PostgreSQL seeded with the {@code orders} schema.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "orders.outbox.relay.enabled=false")
class OrderCreateIntegrationTest {

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

    private Integer customerId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        customerRepository.deleteAll();

        Customer customer = new Customer();
        customer.setName("Alice");
        customer.setEmail("alice+" + UUID.randomUUID() + "@market.test");
        customerId = customerRepository.save(customer).getId();
    }

    @Test
    void createOrder_persistsOrderItemsAndIdempotencyKey_inOneTransaction() {
        String key = UUID.randomUUID().toString();
        var request = new OrderCreateRequest(customerId, List.of(
                new OrderCreateRequest.Item(101, 2),
                new OrderCreateRequest.Item(202, 1)));

        OrderResponse created = orderService.createOrder(key, request);

        // Order head persisted with a computed total and the happy-path status.
        assertThat(created.id()).isNotNull();
        assertThat(created.customerId()).isEqualTo(customerId);
        assertThat(created.status()).isEqualTo("paid");
        assertThat(created.totalCents()).isPositive();
        assertThat(created.createdAt()).isNotNull();

        // Lines + idempotency row committed together with the order.
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(orderItemRepository.count()).isEqualTo(2);
        assertThat(idempotencyKeyRepository.findById(key)).isPresent();

        // Exactly one order_placed event was staged in the outbox, in the same transaction.
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        var event = outboxEventRepository.findAll().get(0);
        assertThat(event.getTopic()).isEqualTo("orders.events");
        assertThat(event.getEventKey()).isEqualTo(String.valueOf(created.id()));
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPayload())
                .contains("\"event_type\"")
                .contains(OrderEvent.ORDER_PLACED)
                .contains("\"order_id\"")
                .contains(String.valueOf(created.id()));
    }

    @Test
    void createOrder_sameKeyTwice_isIdempotentReplay() {
        String key = UUID.randomUUID().toString();
        var request = new OrderCreateRequest(customerId,
                List.of(new OrderCreateRequest.Item(101, 1)));

        OrderResponse first = orderService.createOrder(key, request);
        OrderResponse second = orderService.createOrder(key, request);

        // Same order returned, and nothing duplicated.
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(orderItemRepository.count()).isEqualTo(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);

        // The idempotent replay must NOT stage a second event.
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void createOrder_sameKeyDifferentRequest_returnsConflict() {
        String key = UUID.randomUUID().toString();
        OrderResponse first = orderService.createOrder(key, new OrderCreateRequest(customerId,
                List.of(new OrderCreateRequest.Item(101, 1))));

        // Same key, different payload (quantity changed) -> 409, not a silent replay.
        var differentRequest = new OrderCreateRequest(customerId,
                List.of(new OrderCreateRequest.Item(101, 5)));

        assertThatThrownBy(() -> orderService.createOrder(key, differentRequest))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        // The original order is untouched and no second order/event was created.
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(orderRepository.findById(first.id())).isPresent();
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void createOrder_sameKeyRequestReordered_isStillIdempotentReplay() {
        String key = UUID.randomUUID().toString();
        var request = new OrderCreateRequest(customerId, List.of(
                new OrderCreateRequest.Item(101, 2),
                new OrderCreateRequest.Item(202, 1)));
        // Same lines, different order: the canonical hash must match -> safe replay.
        var reordered = new OrderCreateRequest(customerId, List.of(
                new OrderCreateRequest.Item(202, 1),
                new OrderCreateRequest.Item(101, 2)));

        OrderResponse first = orderService.createOrder(key, request);
        OrderResponse replay = orderService.createOrder(key, reordered);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void createOrder_unknownCustomer_isRejectedAndRollsBack() {
        String key = UUID.randomUUID().toString();
        var request = new OrderCreateRequest(999_999,
                List.of(new OrderCreateRequest.Item(101, 1)));

        assertThatThrownBy(() -> orderService.createOrder(key, request))
                .isInstanceOf(ApiException.class);

        // Nothing was written.
        assertThat(orderRepository.count()).isZero();
        assertThat(orderItemRepository.count()).isZero();
        assertThat(idempotencyKeyRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }
}
