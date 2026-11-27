package com.kmarket.ordersservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * Maps {@code orders.idempotency_keys}: records which order was created for a given
 * client {@code Idempotency-Key}, so a retried {@code POST /orders} is a no-op replay
 * instead of a duplicate order.
 */
@Entity
@Table(name = "idempotency_keys", schema = "orders")
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyKey {

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "order_id", nullable = false)
    private Integer orderId;

    @Column(name = "order_created_at", nullable = false)
    private OffsetDateTime orderCreatedAt;

    /**
     * Fingerprint of the original request body. A retry with the same key but a different
     * payload is a client mistake and is rejected (409) instead of replaying the first order.
     * Nullable for rows created before this column existed.
     */
    @Column(name = "request_hash")
    private String requestHash;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public IdempotencyKey(String idempotencyKey, Integer orderId, OffsetDateTime orderCreatedAt,
                          String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.orderId = orderId;
        this.orderCreatedAt = orderCreatedAt;
        this.requestHash = requestHash;
    }
}
