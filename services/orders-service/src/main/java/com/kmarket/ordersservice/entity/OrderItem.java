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

import java.time.OffsetDateTime;

/**
 * A single line of a customer order from {@code orders.order_items}.
 *
 * <p>The table is range-partitioned on {@code order_created_at} and its database
 * primary key is {@code (id, order_created_at)}. For JPA we use the globally unique
 * {@code SERIAL} {@code id} as the entity identifier. The {@code (order_id,
 * order_created_at)} pair is the composite foreign key back to
 * {@code orders.orders (id, created_at)} and is set by the service before insert.</p>
 */
@Entity
@Table(name = "order_items", schema = "orders")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "order_id", nullable = false)
    private Integer orderId;

    @Column(name = "order_created_at", nullable = false)
    private OffsetDateTime orderCreatedAt;

    @Column(name = "product_id", nullable = false)
    private Integer productId;

    /**
     * Product display name, frozen from the Catalog at order time.
     * Nullable for rows created before this column existed.
     */
    @Column(name = "product_name")
    private String productName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price_cents", nullable = false)
    private Integer unitPriceCents;
}
