package com.kmarket.authservice.security;

import com.kmarket.authservice.config.JwtProperties;
import com.kmarket.authservice.entity.Role;
import com.kmarket.authservice.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Issues and validates signed (HS256) JWT access tokens. */
@Service
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /** Builds a short-lived access token whose subject is the user id and that carries the roles. */
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(properties.accessTokenTtlSeconds());
        List<String> roles = user.getRoles().stream().map(Role::getName).toList();

        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(user.getId()))
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Builds a short-lived access token for a technical service account.
     * The subject is the API-key name and the single carried role is the key's role
     * (typically {@code service_account}).
     */
    public String generateServiceToken(String subject, String roleName) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(properties.accessTokenTtlSeconds());

        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(subject)
                .claim("roles", List.of(roleName))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Parses and verifies the signature/expiry of a token.
     *
     * @throws JwtException if the token is invalid or expired
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtlSeconds();
    }

    public long refreshTokenTtlSeconds() {
        return properties.refreshTokenTtlSeconds();
    }
}
