package com.kmarket.ordersservice.repository;

import com.kmarket.ordersservice.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@code orders.customers}. */
public interface CustomerRepository extends JpaRepository<Customer, Integer> {
}
