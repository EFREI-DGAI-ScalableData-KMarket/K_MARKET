package com.kmarket.authservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Response matching the {@code Error} schema of the contract. */
public record ErrorResponse(
        String code,
        String message,
        @JsonProperty("request_id") String requestId) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, UUID.randomUUID().toString());
    }
}
