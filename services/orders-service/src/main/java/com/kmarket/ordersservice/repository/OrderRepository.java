package com.kmarket.ordersservice.repository;

import com.kmarket.ordersservice.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Data access for {@code orders.orders}. {@link JpaSpecificationExecutor} lets the
 * service build the optional {@code status} / {@code customer_id} filters of
 * {@code GET /orders} dynamically while still getting paginated results and a total count.
 */
public interface OrderRepository
        extends JpaRepository<Order, Integer>, JpaSpecificationExecutor<Order> {
}
