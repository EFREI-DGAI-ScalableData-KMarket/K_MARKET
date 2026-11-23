package com.kmarket.ordersservice.service;

import com.kmarket.ordersservice.domain.OrderStatus;
import com.kmarket.ordersservice.dto.OrderCreateRequest;
import com.kmarket.ordersservice.dto.OrderDetailResponse;
import com.kmarket.ordersservice.dto.OrderListResponse;
import com.kmarket.ordersservice.dto.OrderResponse;
import com.kmarket.ordersservice.entity.Customer;
import com.kmarket.ordersservice.entity.IdempotencyKey;
import com.kmarket.ordersservice.entity.Order;
import com.kmarket.ordersservice.entity.OrderItem;
import com.kmarket.ordersservice.event.OrderEvent;
import com.kmarket.ordersservice.event.OrderEventPublisher;
import com.kmarket.ordersservice.exception.ApiException;
import com.kmarket.ordersservice.repository.CustomerRepository;
import com.kmarket.ordersservice.repository.IdempotencyKeyRepository;
import com.kmarket.ordersservice.repository.OrderItemRepository;
import com.kmarket.ordersservice.repository.OrderRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Business logic behind {@code GET /orders} and {@code POST /orders}. */
@Service
@RequiredArgsConstructor
public class OrderService {

    /**
     * Status of a freshly created order on the happy path. The full Saga would move
     * through {@code stock_reserved} then {@code paid}; with the external stock/payment
     * calls not yet wired, a successfully persisted order is recorded as {@code paid}.
     */
    private static final String CREATED_STATUS = "paid";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CustomerRepository customerRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ProductPricer productPricer;
    private final ProductNameResolver productNameResolver;
    private final OrderEventPublisher orderEventPublisher;

    /**
     * Lists recent orders (most recent first), optionally filtered by {@code status}
     * and/or {@code customerId}, with offset/limit pagination.
     *
     * @param status      optional status filter, {@code null} to disable
     * @param customerId  optional customer filter, {@code null} to disable
     * @param limit       page size (already clamped by the caller)
     * @param offset      number of rows to skip
     * @return the matching page plus the total number of matching rows
     */
    @Transactional(readOnly = true)
    public OrderListResponse listOrders(String status, Integer customerId, int limit, int offset) {
        Specification<Order> spec = buildSpecification(status, customerId);

        int page = offset / limit;
        PageRequest pageable = PageRequest.of(page, limit,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        List<OrderResponse> items = orderRepository.findAll(spec, pageable).stream()
                .map(OrderResponse::from)
                .toList();
        long total = orderRepository.count(spec);

        return new OrderListResponse(items, total);
    }

    /**
     * Reads a single order with its customer and its priced lines, matching the
     * {@code OrderDetail} schema of {@code GET /orders/{order_id}}.
     *
     * @param orderId the order id
     * @return the order detail
     * @throws ApiException {@code 404} if no order has that id
     */
    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(int orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));

        Customer customer = customerRepository.findById(order.getCustomerId()).orElse(null);
        OrderDetailResponse.Customer customerDto = customer == null ? null
                : new OrderDetailResponse.Customer(customer.getId(), customer.getName(), customer.getEmail());

        List<OrderDetailResponse.Item> items = orderItemRepository
                .findByOrderIdOrderById(order.getId()).stream()
                .map(line -> new OrderDetailResponse.Item(
                        line.getProductId(),
                        productNameResolver.productName(line.getProductId()),
                        line.getQuantity(),
                        line.getUnitPriceCents(),
                        line.getUnitPriceCents() * line.getQuantity()))
                .toList();

        return new OrderDetailResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalCents(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                customerDto,
                items);
    }

    /**
     * Updates an order's status (internal / logistics use), enforcing the allowed
     * {@link OrderStatus} transitions.
     *
     * @param orderId   the order id
     * @param rawStatus the requested target status (contract {@code OrderStatus} value)
     * @return the order head after the update
     * @throws ApiException {@code 400} if {@code rawStatus} is not a known status,
     *                      {@code 404} if the order does not exist, or {@code 409} if the
     *                      transition from the current status is not allowed
     */
    @Transactional
    public OrderResponse updateStatus(int orderId, String rawStatus) {
        OrderStatus target;
        try {
            target = OrderStatus.fromValue(rawStatus);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(
                    "Unknown status '" + rawStatus + "'. Known values: " + OrderStatus.knownValues());
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));

        OrderStatus current = OrderStatus.fromValue(order.getStatus());
        if (!current.canTransitionTo(target)) {
            throw ApiException.conflict(
                    "Invalid status transition: " + current.value() + " -> " + target.value());
        }

        order.setStatus(target.value());
        orderRepository.saveAndFlush(order);

        // Stage the lifecycle event in the same transaction (transactional outbox).
        orderEventPublisher.publish(OrderEvent.statusChanged(
                order.getId(), order.getCustomerId(),
                current.value(), target.value(), order.getTotalCents()));

        return OrderResponse.from(order);
    }

    /**
     * Creates an order, its lines and the idempotency record <em>atomically</em>.
     *
     * <p>The whole method runs in a single transaction: either the order, all of its
     * {@code order_items} and the {@code idempotency_keys} row are committed together,
     * or nothing is (rollback on any exception). Pricing is resolved via
     * {@link ProductPricer} (the Catalog seam) and the per-line price is frozen into
     * {@code order_items.unit_price_cents}.</p>
     *
     * <p>Idempotency: if {@code idempotencyKey} was already used, the previously created
     * order is returned unchanged (safe retry) instead of creating a duplicate.</p>
     *
     * @param idempotencyKey the client-supplied {@code Idempotency-Key} header
     * @param request        the validated order payload
     * @return the created (or previously created) order
     */
    @Transactional
    public OrderResponse createOrder(String idempotencyKey, OrderCreateRequest request) {
        // 1. Idempotency replay: same key -> return the order created the first time.
        var existing = idempotencyKeyRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            return orderRepository.findById(key.getOrderId())
                    .map(OrderResponse::from)
                    .orElseThrow(() -> ApiException.conflict(
                            "Idempotency key already used but its order is missing"));
        }

        // 2. Validate the customer exists (FK orders.orders.customer_id -> orders.customers.id).
        if (!customerRepository.existsById(request.customerId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "UNPROCESSABLE", "Unknown customer_id " + request.customerId());
        }

        // 3. Price every line via the Catalog seam and compute the total.
        long totalCents = 0;
        List<OrderItem> lines = new ArrayList<>(request.items().size());
        for (OrderCreateRequest.Item item : request.items()) {
            Integer unitPrice = productPricer.unitPriceCents(item.productId());
            if (unitPrice == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "UNPROCESSABLE", "Unknown or unavailable product_id " + item.productId());
            }
            OrderItem line = new OrderItem();
            line.setProductId(item.productId());
            line.setQuantity(item.quantity());
            line.setUnitPriceCents(unitPrice);
            lines.add(line);
            totalCents += (long) unitPrice * item.quantity();
        }
        if (totalCents > Integer.MAX_VALUE) {
            throw ApiException.badRequest("Order total exceeds the supported maximum");
        }

        // 4. Persist the order head; flush to obtain the DB-generated id + created_at.
        Order order = new Order();
        order.setCustomerId(request.customerId());
        order.setStatus(CREATED_STATUS);
        order.setTotalCents((int) totalCents);
        orderRepository.saveAndFlush(order);

        // 5. Persist the lines, copying the order's (id, created_at) into the composite FK.
        for (OrderItem line : lines) {
            line.setOrderId(order.getId());
            line.setOrderCreatedAt(order.getCreatedAt());
        }
        orderItemRepository.saveAll(lines);

        // 6. Record the idempotency key. A concurrent duplicate request loses the race
        //    on the primary key and surfaces as a 409 conflict.
        try {
            idempotencyKeyRepository.saveAndFlush(
                    new IdempotencyKey(idempotencyKey, order.getId(), order.getCreatedAt()));
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("Order already created for this Idempotency-Key");
        }

        // 7. Stage the creation event in the same transaction (transactional outbox).
        //    Reached only on genuine first creation; the idempotent replay returns earlier.
        orderEventPublisher.publish(OrderEvent.placed(
                order.getId(), order.getCustomerId(), order.getStatus(), order.getTotalCents()));

        return OrderResponse.from(order);
    }



    private Specification<Order> buildSpecification(String status, Integer customerId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
