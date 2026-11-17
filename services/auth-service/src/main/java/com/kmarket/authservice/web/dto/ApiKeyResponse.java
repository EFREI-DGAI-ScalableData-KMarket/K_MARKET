package com.kmarket.authservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kmarket.authservice.entity.ApiKey;

import java.time.OffsetDateTime;

/** Response matching the {@code ApiKey} schema of the contract. */
public record ApiKeyResponse(
        Integer id,
        String name,
        @JsonProperty("role_id") Integer roleId,
        @JsonProperty("is_active") boolean active,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    public static ApiKeyResponse from(ApiKey apiKey) {
        return new ApiKeyResponse(apiKey.getId(), apiKey.getName(),
                apiKey.getRole().getId(), apiKey.isActive(), apiKey.getCreatedAt());
    }
}
