package com.kmarket.ordersservice.controller;

import com.kmarket.ordersservice.dto.OrderCreateRequest;
import com.kmarket.ordersservice.dto.OrderDetailResponse;
import com.kmarket.ordersservice.dto.OrderListResponse;
import com.kmarket.ordersservice.dto.OrderResponse;
import com.kmarket.ordersservice.dto.OrderStatusUpdateRequest;
import com.kmarket.ordersservice.exception.ApiException;
import com.kmarket.ordersservice.security.OrderOwnership;
import com.kmarket.ordersservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** REST endpoints for orders. Requires a valid Bearer JWT (see SecurityConfig). */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    /** Contract default page size. */
    private static final int DEFAULT_LIMIT = 50;

    /** Contract maximum page size. */
    private static final int MAX_LIMIT = 200;

    private final OrderService orderService;
    private final OrderOwnership orderOwnership;

    /**
     * {@code GET /orders} — lists recent orders, optionally filtered by
     * {@code status} and/or {@code customer_id}, with offset/limit pagination.
     *
     * <p>Role rule: {@code admin}/{@code analyst}/{@code logistics}/{@code service_account}
     * (see SecurityConfig). Ownership: non-privileged callers are scoped to their own
     * orders; the requested {@code customer_id} cannot point at another customer.</p>
     */
    @GetMapping
    public OrderListResponse listOrders(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(name = "customer_id", required = false) Integer customerId,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "0") int offset) {

        if (limit < 1) {
            throw ApiException.badRequest("limit must be >= 1");
        }
        if (offset < 0) {
            throw ApiException.badRequest("offset must be >= 0");
        }
        int effectiveLimit = Math.min(limit, MAX_LIMIT);

        Integer effectiveCustomerId = orderOwnership.effectiveCustomerFilter(authentication, customerId);
        return orderService.listOrders(status, effectiveCustomerId, effectiveLimit, offset);
    }

    /**
     * {@code POST /orders} — creates an order (persistence core of the Saga).
     *
     * <p>The {@code Idempotency-Key} header (a UUID) makes retries safe: replaying the
     * same key returns the order created the first time instead of a duplicate. The
     * order, its lines and the idempotency record are written in one transaction.</p>
     *
     * <p>Ownership: a regular caller may only order for their own account (JWT
     * {@code sub} must equal {@code customer_id}); {@code service_account} / {@code admin}
     * may order for any customer (Saga / tooling). See {@link OrderOwnership}.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OrderCreateRequest request) {

        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.isEmpty()) {
            throw ApiException.badRequest("Idempotency-Key header is required");
        }
        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Idempotency-Key must be a UUID");
        }

        orderOwnership.assertCanCreateFor(authentication, request.customerId());

        return orderService.createOrder(key, request);
    }

    /**
     * {@code GET /orders/{order_id}} — reads a single order with its customer and its
     * priced lines ({@code OrderDetail}). Returns {@code 404} if the order is unknown.
     *
     * <p>Ownership: a non-privileged caller may only read their own order ({@code 403}
     * otherwise); {@code admin}/{@code analyst}/{@code logistics}/{@code service_account}
     * may read any order.</p>
     */
    @GetMapping("/{order_id}")
    public OrderDetailResponse getOrder(
            Authentication authentication,
            @PathVariable("order_id") int orderId) {
        OrderDetailResponse order = orderService.getOrder(orderId);
        orderOwnership.assertCanRead(authentication, order.customerId());
        return order;
    }

    /**
     * {@code PATCH /orders/{order_id}/status} — updates the order status (internal /
     * logistics use). Rejects unknown statuses with {@code 400}, an unknown order with
     * {@code 404} and a forbidden transition (e.g. {@code delivered -> pending}) with
     * {@code 409}.
     */
    @PatchMapping("/{order_id}/status")
    public OrderResponse updateStatus(
            @PathVariable("order_id") int orderId,
            @Valid @RequestBody OrderStatusUpdateRequest request) {

        return orderService.updateStatus(orderId, request.status());
    }
}
