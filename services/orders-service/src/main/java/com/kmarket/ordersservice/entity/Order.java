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
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * A customer order from {@code orders.orders}.
 *
 * <p>The table is range-partitioned by month on {@code created_at} and its database
 * primary key is the composite {@code (id, created_at)} (a partitioned table's PK must
 * include the partition key). Both key columns are DB-generated — {@code id} by the
 * {@code SERIAL} sequence and {@code created_at} by {@code DEFAULT now()}. JPA/Hibernate
 * cannot model that composite as an {@code @IdClass} while auto-generating both members
 * (identity generation requires a single-column id), so the entity uses the globally
 * unique {@code SERIAL} {@code id} as its {@code @Id}; {@code created_at} / {@code
 * updated_at} are read back after insert. The real composite key is represented by the
 * value type {@link OrderId} (e.g. for the composite FK from {@code order_items}).</p>
 */
@Entity
@Table(name = "orders", schema = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "customer_id", nullable = false)
    private Integer customerId;

    @Column(nullable = false)
    private String status;

    @Column(name = "total_cents", nullable = false)
    private Integer totalCents;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** The composite database identifier {@code (id, created_at)} for this order. */
    public OrderId getOrderId() {
        return new OrderId(id, createdAt);
    }

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
