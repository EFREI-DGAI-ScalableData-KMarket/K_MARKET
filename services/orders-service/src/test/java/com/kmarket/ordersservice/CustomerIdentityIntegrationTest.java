package com.kmarket.ordersservice;

import com.kmarket.ordersservice.dto.OrderCreateRequest;
import com.kmarket.ordersservice.dto.OrderResponse;
import com.kmarket.ordersservice.entity.Customer;
import com.kmarket.ordersservice.exception.ApiException;
import com.kmarket.ordersservice.repository.CustomerRepository;
import com.kmarket.ordersservice.repository.IdempotencyKeyRepository;
import com.kmarket.ordersservice.repository.OrderItemRepository;
import com.kmarket.ordersservice.repository.OrderRepository;
import com.kmarket.ordersservice.repository.OutboxEventRepository;
import com.kmarket.ordersservice.security.OrderOwnership;
import com.kmarket.ordersservice.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the customer identity model
 * {@code orders.customers.id == auth.users.id == JWT sub}, against a real (Testcontainers)
 * PostgreSQL seeded with the {@code orders} schema. Verifies that the customer id is an
 * explicitly-assigned PK (no DB sequence) and that ownership checks compare the JWT subject
 * directly with {@code customer_id}.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestCatalogConfiguration.class,
        TestStockConfiguration.class, TestPaymentConfiguration.class})
class CustomerIdentityIntegrationTest {

    @Autowired private OrderService orderService;
    @Autowired private OrderOwnership orderOwnership;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private TestStockConfiguration.ControllableStockClient stockClient;
    @Autowired private TestPaymentConfiguration.ControllablePaymentClient paymentClient;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        customerRepository.deleteAll();
        stockClient.outcome.set(com.kmarket.ordersservice.client.StockReservationOutcome.RESERVED);
        stockClient.commitOutcome.set(com.kmarket.ordersservice.client.StockOperationOutcome.DONE);
        paymentClient.captureOutcome.set(com.kmarket.ordersservice.client.PaymentOutcome.SUCCEEDED);
    }

    /** Persists a customer whose id is explicitly assigned (= Auth user id). */
    private Customer customer(int id) {
        Customer c = new Customer();
        c.setId(id);
        c.setName("User " + id);
        c.setEmail("user" + id + "+" + UUID.randomUUID() + "@market.test");
        return customerRepository.save(c);
    }

    /** A customer JWT whose subject is the numeric Auth user id (= customer id). */
    private Authentication customerAuth(int userId) {
        return new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority("ROLE_customer")));
    }

    private Authentication auth(int userId, String role) {
        return new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    // 1. Auth user id 15 corresponds to customer id 15 (no DB-generated id).
    @Test
    void customerPersists_withExplicitlyAssignedId_matchingAuthUserId() {
        Customer saved = customer(15);
        assertThat(saved.getId()).isEqualTo(15);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(customerRepository.findById(15)).isPresent();
    }

    // 2. Customer 15 can create an order for customer 15.
    @Test
    void customer15_canCreateOrderForSelf() {
        customer(15);
        orderOwnership.assertCanCreateFor(customerAuth(15), 15);  // no exception

        OrderResponse created = orderService.createOrder(UUID.randomUUID().toString(),
                new OrderCreateRequest(15, List.of(new OrderCreateRequest.Item(101, 1))));
        assertThat(created.customerId()).isEqualTo(15);
        assertThat(created.status()).isEqualTo("paid");
    }

    // 3. Customer 15 cannot create an order for customer 16.
    @Test
    void customer15_cannotCreateOrderForCustomer16() {
        assertThatThrownBy(() -> orderOwnership.assertCanCreateFor(customerAuth(15), 16))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // 4 & 5. Customer 15 reads own order; reading customer 16's order is 404 (non-disclosure).
    @Test
    void customer15_canReadOwnOrder_butNotCustomer16sOrder() {
        customer(15);
        customer(16);
        // Owner check: same id passes, different id -> 404 (not 403).
        orderOwnership.assertCanReadOrNotFound(customerAuth(15), 15, 100);  // own -> ok
        assertThatThrownBy(() -> orderOwnership.assertCanReadOrNotFound(customerAuth(15), 16, 200))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // 6. Customer 15 only sees their own orders in GET /orders (listing defaults to own id,
    //    and reading another customer's id is refused).
    @Test
    void customer15_listingIsScopedToOwnId() {
        Authentication me = customerAuth(15);
        // No requested filter -> the controller defaults it to the caller's own subject id.
        Integer defaulted = orderOwnership.parseSubjectId(me);
        assertThat(defaulted).isEqualTo(15);
        // Requesting own id -> allowed (no exception).
        orderOwnership.assertCanRead(me, 15);
        // Requesting another customer's id -> refused. A customer must not even learn that
        // another customer's data exists, so assertCanRead denies with 404 (non-disclosure).
        assertThatThrownBy(() -> orderOwnership.assertCanRead(me, 16))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // 7. Admin / service-account may act on any customer (create for others, read any, list any).
    @Test
    void privilegedRoles_bypassOwnership() {
        // admin and service_account may create for another customer.
        orderOwnership.assertCanCreateFor(auth(1, "admin"), 15);
        orderOwnership.assertCanCreateFor(auth(2, "service_account"), 16);
        // admin may read any customer's order (no exception).
        orderOwnership.assertCanReadOrNotFound(auth(1, "admin"), 999, 100);
        // Privileged listing: they may scope to any customer id (no exception from assertCanRead).
        orderOwnership.assertCanRead(auth(2, "service_account"), 999);
        orderOwnership.assertCanRead(auth(1, "admin"), 123);
    }

    // 8. Analyst / logistics: read-only privileged (may read/list any, but NOT create for others).
    @Test
    void analystAndLogistics_mayReadAnyButNotCreateForOthers() {
        // May read any order and list any customer (privileged for read).
        orderOwnership.assertCanReadOrNotFound(auth(3, "analyst"), 999, 100);
        orderOwnership.assertCanRead(auth(4, "logistics"), 999);
        // But may NOT create an order for another customer (not in CREATE_FOR_OTHERS set).
        assertThatThrownBy(() -> orderOwnership.assertCanCreateFor(auth(3, "analyst"), 15))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
