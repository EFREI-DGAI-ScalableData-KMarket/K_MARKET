package com.kmarket.authservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /auth/refresh}. */
public record RefreshRequest(
        @JsonProperty("refresh_token") @NotBlank String refreshToken) {
}
