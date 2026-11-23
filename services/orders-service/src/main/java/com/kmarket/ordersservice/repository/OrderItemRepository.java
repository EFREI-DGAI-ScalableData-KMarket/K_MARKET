package com.kmarket.ordersservice.repository;

import com.kmarket.ordersservice.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Data access for {@code orders.order_items}. */
public interface OrderItemRepository extends JpaRepository<OrderItem, Integer> {

    /** The lines of a given order, ordered by their id for a stable response. */
    List<OrderItem> findByOrderIdOrderById(Integer orderId);
}
