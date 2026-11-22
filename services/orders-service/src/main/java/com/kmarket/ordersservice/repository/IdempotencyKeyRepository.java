package com.kmarket.ordersservice.repository;

import com.kmarket.ordersservice.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@code orders.idempotency_keys}. */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {
}
