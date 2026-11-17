package com.kmarket.authservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kmarket.authservice.entity.ApiKey;

import java.time.OffsetDateTime;

/**
 * Response matching the {@code ApiKeyCreated} schema of the contract.
 * Carries the extra {@code key} field: the clear value shown only once at creation.
 */
public record ApiKeyCreatedResponse(
        Integer id,
        String name,
        @JsonProperty("role_id") Integer roleId,
        @JsonProperty("is_active") boolean active,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        String key) {

    public static ApiKeyCreatedResponse from(ApiKey apiKey, String clearKey) {
        return new ApiKeyCreatedResponse(apiKey.getId(), apiKey.getName(),
                apiKey.getRole().getId(), apiKey.isActive(), apiKey.getCreatedAt(), clearKey);
    }
}
