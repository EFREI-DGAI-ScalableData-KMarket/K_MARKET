package com.kmarket.ordersservice;

import com.kmarket.ordersservice.dto.OrderCreateRequest;
import com.kmarket.ordersservice.entity.Customer;
import com.kmarket.ordersservice.entity.OutboxEvent;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Proves the outbox event is persisted <em>atomically</em> with the order: if staging
 * the event fails, the whole {@code createOrder} transaction rolls back and neither the
 * order, its lines, the idempotency key, nor any outbox row survive.
 *
 * <p>Runs against a real (Testcontainers) PostgreSQL. The outbox repository is spied so
 * its {@code save} throws after the order/items have already been written in the same
 * transaction — the only way the rollback can leave a clean database is if all four
 * writes share one transaction.</p>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestCatalogConfiguration.class})
@TestPropertySource(properties = "orders.outbox.relay.enabled=false")
class OutboxAtomicityIntegrationTest {

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

    @MockitoSpyBean
    private OutboxEventRepository outboxEventRepository;

    private Integer customerId;

    @BeforeEach
    void setUp() {
        idempotencyKeyRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        outboxEventRepository.deleteAll();
        customerRepository.deleteAll();

        Customer customer = new Customer();
        customer.setName("Bob");
        customer.setEmail("bob+" + UUID.randomUUID() + "@market.test");
        customerId = customerRepository.save(customer).getId();
    }

    @Test
    void createOrder_whenOutboxStagingFails_rollsBackEverything() {
        // Force the outbox INSERT to fail — it happens after the order + items are written.
        doThrow(new RuntimeException("boom: simulated outbox failure"))
                .when(outboxEventRepository).save(any(OutboxEvent.class));

        String key = UUID.randomUUID().toString();
        var request = new OrderCreateRequest(customerId,
                List.of(new OrderCreateRequest.Item(101, 2)));

        assertThatThrownBy(() -> orderService.createOrder(key, request))
                .isInstanceOf(RuntimeException.class);

        // The order, its lines and the idempotency key rolled back together with the
        // failed outbox insert: a single atomic transaction, nothing half-written.
        assertThat(orderRepository.count()).isZero();
        assertThat(orderItemRepository.count()).isZero();
        assertThat(idempotencyKeyRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }
}
