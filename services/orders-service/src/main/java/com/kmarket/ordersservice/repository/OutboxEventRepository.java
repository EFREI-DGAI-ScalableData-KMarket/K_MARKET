package com.kmarket.ordersservice.repository;

import com.kmarket.ordersservice.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Data access for {@code orders.outbox}. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of not-yet-published events, oldest first. {@code FOR UPDATE SKIP
     * LOCKED} lets several relay instances run concurrently without reading the same
     * rows, so publishing scales horizontally and a stuck row never blocks the rest.
     *
     * @param batch maximum number of rows to claim
     * @return the locked pending rows (must be consumed inside the caller's transaction)
     */
    @Query(value = "SELECT * FROM orders.outbox WHERE published_at IS NULL "
            + "ORDER BY id LIMIT :batch FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findPendingBatch(@Param("batch") int batch);

    /** Number of events still awaiting publication (for metrics / tests). */
    long countByPublishedAtIsNull();
}
