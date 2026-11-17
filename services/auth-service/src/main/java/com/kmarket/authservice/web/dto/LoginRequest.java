package com.kmarket.authservice.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /auth/login}. */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {
}
