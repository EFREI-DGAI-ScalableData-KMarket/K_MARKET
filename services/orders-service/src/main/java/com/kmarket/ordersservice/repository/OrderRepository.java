package com.kmarket.ordersservice.repository;

import com.kmarket.ordersservice.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Data access for {@code orders.orders}.
 *
 * <p>The table's database primary key is the composite {@code (id, created_at)}, but the
 * {@link Order} entity is keyed by the globally unique {@code SERIAL} {@code id} (see the
 * entity Javadoc for why the composite cannot be an {@code @IdClass} here), so this
 * repository is keyed by {@code Integer} and {@code findById(id)} resolves an order
 * unambiguously. The composite key value is available via {@link Order#getOrderId()}.</p>
 *
 * <p>{@link JpaSpecificationExecutor} lets the
 * service build the optional {@code status} / {@code customer_id} filters of
 * {@code GET /orders} dynamically while still getting paginated results and a total count.
 */
public interface OrderRepository
        extends JpaRepository<Order, Integer>, JpaSpecificationExecutor<Order> {
}
