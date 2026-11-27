package com.kmarket.ordersservice.entity;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Composite primary key of {@link OrderItem} ({@code id} + {@code order_created_at}),
 * required because {@code orders.order_items} is range-partitioned on {@code order_created_at}.
 */
public class OrderItemId implements Serializable {

    private Integer id;
    private OffsetDateTime orderCreatedAt;

    public OrderItemId() {
    }

    public OrderItemId(Integer id, OffsetDateTime orderCreatedAt) {
        this.id = id;
        this.orderCreatedAt = orderCreatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderItemId other)) {
            return false;
        }
        return Objects.equals(id, other.id) && Objects.equals(orderCreatedAt, other.orderCreatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, orderCreatedAt);
    }
}
