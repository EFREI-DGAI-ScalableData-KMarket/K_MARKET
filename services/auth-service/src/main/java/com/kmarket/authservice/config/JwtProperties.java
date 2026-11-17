package com.kmarket.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the {@code auth.jwt.*} properties. */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
        String secret,
        long accessTokenTtlSeconds,
        long refreshTokenTtlSeconds,
        String issuer) {
}
