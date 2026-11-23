package com.kmarket.ordersservice.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * A pending (or published) domain event from {@code orders.outbox}.
 *
 * <p>Implements the <em>transactional outbox</em> pattern used across the platform
 * (mirrors {@code inventory.outbox}): the service writes an event row in the <em>same
 * database transaction</em> as the business change, so the order and its event commit
 * or roll back together. A relay later publishes unpublished rows to Kafka and stamps
 * {@code published_at}. Delivery is at-least-once, so consumers deduplicate on the
 * {@code event_id} carried inside {@link #payload}.</p>
 */
@Entity
@Table(name = "outbox", schema = "orders")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Destination Kafka topic (e.g. {@code orders.events}). */
    @Column(nullable = false)
    private String topic;

    /** Kafka message key; keying by {@code order_id} keeps one order's events ordered. */
    @Column(name = "event_key", nullable = false)
    private String eventKey;

    /** The event body as a JSON document (stored in a {@code jsonb} column). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** {@code null} until the relay has published the event to Kafka. */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    public OutboxEvent(String topic, String eventKey, String payload) {
        this.topic = topic;
        this.eventKey = eventKey;
        this.payload = payload;
    }
}
