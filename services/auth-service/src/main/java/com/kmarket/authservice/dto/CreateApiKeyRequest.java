package com.kmarket.authservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /admin/api-keys} — create a technical-service API key. */
public record CreateApiKeyRequest(
        @NotBlank String name,
        @NotNull @JsonProperty("role_id") Integer roleId) {
}
