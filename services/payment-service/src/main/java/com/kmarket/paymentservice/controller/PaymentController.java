package com.kmarket.paymentservice.controller;

import com.kmarket.paymentservice.dto.PaymentCreateRequest;
import com.kmarket.paymentservice.dto.PaymentResponse;
import com.kmarket.paymentservice.exception.ApiException;
import com.kmarket.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** REST endpoints for payments. Requires a valid Bearer JWT (see SecurityConfig). */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * {@code POST /payments} — captures a payment idempotently (Saga step 3).
     *
     * <p>The {@code Idempotency-Key} header (a UUID) makes retries safe: replaying the same
     * key returns the payment created the first time instead of charging twice. A payment
     * strictly over 200 € is declined with {@code 422}; reusing the key for a different
     * order/amount is a {@code 409}.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentCreateRequest request) {

        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.isEmpty()) {
            throw ApiException.badRequest("Idempotency-Key header is required");
        }
        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Idempotency-Key must be a UUID");
        }

        return paymentService.capture(key, request);
    }

    /**
     * {@code GET /payments/{order_id}} — reads the payment of an order. Lets Orders Service
     * recover the original result after a lost response, without charging again. Returns
     * {@code 404} if the order has no payment.
     */
    @GetMapping("/{order_id}")
    public PaymentResponse getPayment(@PathVariable("order_id") int orderId) {
        return paymentService.getByOrderId(orderId);
    }

    /**
     * {@code POST /payments/{order_id}/refund} — refunds a succeeded payment (Saga
     * compensation). Returns {@code 404} if unknown, {@code 409} if not refundable.
     */
    @PostMapping("/{order_id}/refund")
    public PaymentResponse refundPayment(@PathVariable("order_id") int orderId) {
        return paymentService.refund(orderId);
    }
}
