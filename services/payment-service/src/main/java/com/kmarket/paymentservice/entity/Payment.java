package com.kmarket.paymentservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * A payment row from {@code payments.payments}.
 *
 * <p>The database key is the {@code SERIAL} {@code id}. Idempotency is enforced at the
 * database level by {@code UNIQUE (idempotency_key)}: two concurrent requests that reuse
 * the same key cannot both insert, so a duplicate charge is impossible even under a race
 * (the loser gets a constraint violation, handled in the service).</p>
 *
 * <p>Contract/schema reconciliation: the {@code Payment} response schema carries
 * {@code currency} and {@code updated_at}, but the table has neither column (and the
 * {@code payments_svc} role cannot run DDL). The entity therefore maps only the real
 * columns; {@code currency} is validated/echoed as {@code EUR} and {@code updated_at} is
 * omitted by the response mapper. The {@code CHECK (status = 'failed' OR failure_reason
 * IS NULL)} constraint means {@code failure_reason} is only set on a {@code failed} row.</p>
 */
@Entity
@Table(name = "payments", schema = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "order_id", nullable = false)
    private Integer orderId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "amount_cents", nullable = false)
    private Integer amountCents;

    @Column(nullable = false)
    private String status;

    /** Only populated when {@code status = 'failed'} (DB CHECK constraint). */
    @Column(name = "failure_reason")
    private String failureReason;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
