package com.kmarket.ordersservice.entity;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * Composite identifier value for an {@link Order}, matching the database primary key
 * {@code (id, created_at)} that range-partitioning by month forces on
 * {@code orders.orders} (a partitioned table's PK must include the partition key).
 *
 * <p>This is a plain value type, not a JPA {@code @IdClass}: both key columns are
 * DB-generated ({@code SERIAL} + {@code DEFAULT now()}), and JPA/Hibernate cannot
 * auto-generate both members of a composite id (identity generation requires a single
 * column). {@link Order} therefore keeps the globally unique {@code id} as its {@code @Id}
 * and exposes the full composite via {@link Order#getOrderId()} — useful wherever the
 * real key is needed, e.g. the composite FK {@code (order_id, order_created_at)} from
 * {@code order_items}.</p>
 */
public record OrderId(Integer id, OffsetDateTime createdAt) implements Serializable {
}
