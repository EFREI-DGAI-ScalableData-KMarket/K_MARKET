package com.kmarket.authservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response matching the {@code TokenPair} schema of the contract. */
public record TokenPair(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn) {

    public static TokenPair bearer(String accessToken, String refreshToken, long expiresIn) {
        return new TokenPair(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
