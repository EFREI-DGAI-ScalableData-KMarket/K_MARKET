package com.kmarket.ordersservice.service;

import com.kmarket.ordersservice.catalog.CatalogProduct;
import com.kmarket.ordersservice.catalog.ProductCatalog;
import com.kmarket.ordersservice.client.StockClient;
import com.kmarket.ordersservice.client.StockReservationOutcome;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Business logic behind {@code GET /orders} and {@code POST /orders}. */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CustomerRepository customerRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ProductCatalog productCatalog;
    private final StockClient stockClient;
    private final OrderEventPublisher orderEventPublisher;

    /**
     * Self-reference used to invoke the {@code @Transactional} persistence/transition steps
     * through the Spring proxy (a plain {@code this.method()} call would bypass the
     * transaction advice). Each step is its own short transaction, with the external Stock
     * call made in between — never inside a database transaction.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @Lazy
    private OrderService self;

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

        List<OrderDetailResponse.Item> items = orderItemRepository.findByOrderIdOrderById(order.getId()).stream()
                .map(line -> {
                    String productName = line.getProductName() != null
                            ? line.getProductName()
                            : "Product #" + line.getProductId();
                    return new OrderDetailResponse.Item(
                            line.getProductId(),
                            productName,
                            line.getQuantity(),
                            line.getUnitPriceCents(),
                            line.getUnitPriceCents() * line.getQuantity());
                })
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
     * Creates an order and runs the first Saga step: reserve the stock.
     *
     * <p>Orchestration (external calls are kept <em>outside</em> any database transaction):</p>
     * <ol>
     *   <li><b>Tx1</b> — {@link #persistPendingOrder}: validate idempotency/customer, price the
     *       lines via the Catalog, and persist the order as {@code pending} with its lines,
     *       idempotency key and {@code order_placed} event, all atomically.</li>
     *   <li><b>Stock reserve</b> (no transaction): {@code POST /stock/reserve}, idempotent by
     *       {@code order_id}.</li>
     *   <li><b>Tx2</b> — depending on the outcome:
     *     <ul>
     *       <li>{@code RESERVED} → transition {@code pending → stock_reserved}.</li>
     *       <li>{@code INSUFFICIENT} → transition {@code pending → rejected} (no payment is
     *           attempted) and surface {@code 422} to the caller; the rejected order stays
     *           persisted and visible.</li>
     *       <li>{@code UNCERTAIN} (timeout / transport) → leave the order {@code pending} and
     *           surface {@code 502}; a reconciliation job must resolve it later. This is
     *           deliberately NOT treated as insufficient stock.</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>Idempotency: replaying the same key with the same request returns the existing order
     * (the stock reserve is itself idempotent by {@code order_id}); a different payload is a
     * {@code 409}.</p>
     *
     * @param idempotencyKey the client-supplied {@code Idempotency-Key} header
     * @param request        the validated order payload
     * @return the created order after the reservation attempt
     */
    public OrderResponse createOrder(String idempotencyKey, OrderCreateRequest request) {
        // Tx1: persist the order as pending (or return the existing one on idempotent replay).
        PersistResult persisted = self.persistPendingOrder(idempotencyKey, request);
        if (persisted.replay()) {
            return persisted.response();
        }

        int orderId = persisted.response().id();

        // Stock reservation — external call, deliberately outside any DB transaction.
        StockReservationOutcome outcome = stockClient.reserve(orderId, persisted.lines());

        // Tx2: record the outcome.
        switch (outcome) {
            case RESERVED -> {
                return self.transition(orderId, OrderStatus.STOCK_RESERVED);
            }
            case INSUFFICIENT -> {
                self.transition(orderId, OrderStatus.REJECTED);
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "INSUFFICIENT_STOCK",
                        "Insufficient stock: order " + orderId + " was rejected (no payment attempted)");
            }
            default -> throw ApiException.upstream(
                    "Stock reservation outcome is uncertain for order " + orderId
                            + "; the order stays pending for reconciliation");
        }
    }

    /**
     * Tx1 of {@link #createOrder}: validates and persists the order as {@code pending} with
     * its lines, idempotency key and {@code order_placed} event — all atomically. Makes no
     * external calls other than the Catalog lookup (read-only, idempotent).
     */
    @Transactional
    public PersistResult persistPendingOrder(String idempotencyKey, OrderCreateRequest request) {
        String requestHash = requestHash(request);

        // 1. Idempotency replay. Same key must mean the same request:
        //    - identical payload -> return the order created the first time (safe retry);
        //    - different payload -> 409, the key is being reused for a different order.
        var existing = idempotencyKeyRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getRequestHash() != null && !key.getRequestHash().equals(requestHash)) {
                throw ApiException.conflict(
                        "Idempotency-Key already used with a different request payload");
            }
            OrderResponse replay = orderRepository.findById(key.getOrderId())
                    .map(OrderResponse::from)
                    .orElseThrow(() -> ApiException.conflict(
                            "Idempotency key already used but its order is missing"));
            return PersistResult.replay(replay);
        }

        // 2. Validate the customer exists (FK orders.orders.customer_id -> orders.customers.id).
        if (!customerRepository.existsById(request.customerId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "UNPROCESSABLE", "Unknown customer_id " + request.customerId());
        }

        // 3. Validate products and fetch prices from the Catalog in a single call
        Set<Integer> productIds = new LinkedHashSet<>();
        request.items().forEach(item -> productIds.add(item.productId()));
        Map<Integer, CatalogProduct> products = productCatalog.fetchByIds(productIds);

        long totalCents = 0;
        List<OrderItem> lines = new ArrayList<>(request.items().size());
        for (OrderCreateRequest.Item item : request.items()) {
            CatalogProduct product = products.get(item.productId());
            if (product == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "UNPROCESSABLE", "Unknown product_id " + item.productId());
            }
            if (!product.active()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "UNPROCESSABLE", "Product " + item.productId() + " is no longer available");
            }
            OrderItem line = new OrderItem();
            line.setProductId(item.productId());
            line.setProductName(product.name());
            line.setQuantity(item.quantity());
            line.setUnitPriceCents(product.priceCents());
            lines.add(line);
            totalCents += (long) product.priceCents() * item.quantity();
        }
        if (totalCents > Integer.MAX_VALUE) {
            throw ApiException.badRequest("Order total exceeds the supported maximum");
        }

        // 4. Persist the order head as PENDING; flush to obtain the DB-generated id + created_at.
        Order order = new Order();
        order.setCustomerId(request.customerId());
        order.setStatus(OrderStatus.PENDING.value());
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
                    new IdempotencyKey(idempotencyKey, order.getId(), order.getCreatedAt(), requestHash));
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("Order already created for this Idempotency-Key");
        }

        // 7. Stage the creation event in the same transaction (transactional outbox).
        orderEventPublisher.publish(OrderEvent.placed(
                order.getId(), order.getCustomerId(), order.getStatus(), order.getTotalCents()));

        return PersistResult.created(OrderResponse.from(order), lines);
    }

    /**
     * Tx2 of {@link #createOrder}: transitions a just-persisted order to {@code target}
     * (e.g. {@code stock_reserved} or {@code rejected}) and stages the status-change event.
     * Enforces the same transition rules as {@link #updateStatus}.
     */
    @Transactional
    public OrderResponse transition(int orderId, OrderStatus target) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
        OrderStatus current = OrderStatus.fromValue(order.getStatus());
        if (!current.canTransitionTo(target)) {
            throw ApiException.conflict(
                    "Invalid status transition: " + current.value() + " -> " + target.value());
        }
        order.setStatus(target.value());
        orderRepository.saveAndFlush(order);
        orderEventPublisher.publish(OrderEvent.statusChanged(
                order.getId(), order.getCustomerId(), current.value(), target.value(), order.getTotalCents()));
        return OrderResponse.from(order);
    }

    /**
     * Outcome of {@link #persistPendingOrder}: the order (as a response), its lines (needed
     * for the stock-reserve call), and whether this was an idempotent replay (in which case
     * the Saga must not run again).
     */
    public record PersistResult(OrderResponse response, List<OrderItem> lines, boolean replay) {
        static PersistResult created(OrderResponse response, List<OrderItem> lines) {
            return new PersistResult(response, lines, false);
        }

        static PersistResult replay(OrderResponse response) {
            return new PersistResult(response, List.of(), true);
        }
    }



    /**
     * Stable fingerprint of a create request used to detect {@code Idempotency-Key} reuse
     * with a different payload. The representation is canonical (customer id plus the
     * {@code product_id:quantity} lines sorted by product id), so item ordering does not
     * change the hash.
     */
    private String requestHash(OrderCreateRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("customer=").append(request.customerId()).append(';');
        request.items().stream()
                .sorted(Comparator.comparing(OrderCreateRequest.Item::productId))
                .forEach(item -> canonical.append(item.productId())
                        .append(':').append(item.quantity()).append(','));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JDK; this cannot happen on a standard runtime.
            throw new IllegalStateException("SHA-256 not available", ex);
        }
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
