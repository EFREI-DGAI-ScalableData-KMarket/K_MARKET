package com.kmarket.authservice.repository;

import com.kmarket.authservice.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Integer> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    boolean existsByName(String name);
}
