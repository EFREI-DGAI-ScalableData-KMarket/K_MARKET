package com.kmarket.paymentservice.repository;

import com.kmarket.paymentservice.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Data access for {@code payments.payments}. */
public interface PaymentRepository extends JpaRepository<Payment, Integer> {

    /** Looks up an existing payment by its client {@code Idempotency-Key} (UNIQUE column). */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * Looks up the payment of an order. Used by {@code GET /payments/{order_id}} (Orders
     * Service timeout recovery) and by the refund flow. One payment per order is expected;
     * the most recent row wins if several ever share an {@code order_id}.
     */
    Optional<Payment> findFirstByOrderIdOrderByIdDesc(Integer orderId);
}
