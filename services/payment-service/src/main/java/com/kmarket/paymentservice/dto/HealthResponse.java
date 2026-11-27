package com.kmarket.paymentservice.dto;

/** Response matching the {@code /health} schema of the contract. */
public record HealthResponse(
        String status,
        String version) {

    public static HealthResponse ok() {
        return new HealthResponse("ok", "v1");
    }
}
