package com.kmarket.ordersservice.client;

import com.kmarket.ordersservice.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Obtains a short-lived {@code service_account} JWT from auth-service by exchanging the
 * orders API key (decision D6: services authenticate with their own technical identity,
 * never by forwarding the end user's token or self-minting with the shared secret).
 *
 * <p>Calls {@code POST {auth}/auth/token} with the {@code X-API-Key} header and caches the
 * returned access token until shortly before it expires. Thread-safe via a synchronized
 * refresh.</p>
 */
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

    /** Refresh this long before the token's actual expiry to avoid using an almost-expired token. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    /** Fallback lifetime if auth-service does not report {@code expires_in}. */
    private static final long DEFAULT_TTL_SECONDS = 900;

    private final RestClient restClient;
    private final String apiKey;

    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public ServiceTokenProvider(RestClient.Builder builder, String authBaseUrl, String apiKey) {
        this.restClient = builder.baseUrl(authBaseUrl).build();
        this.apiKey = apiKey;
    }

    /**
     * @return a valid {@code service_account} bearer token
     * @throws ApiException {@code 502} if auth-service is unreachable or the key is rejected
     */
    public String getToken() {
        String token = cachedToken;
        if (token != null && Instant.now().isBefore(expiresAt.minus(EXPIRY_MARGIN))) {
            return token;
        }
        return refresh();
    }

    private synchronized String refresh() {
        // Re-check under lock: another thread may have refreshed while we waited.
        if (cachedToken != null && Instant.now().isBefore(expiresAt.minus(EXPIRY_MARGIN))) {
            return cachedToken;
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw ApiException.upstream("ORDERS_API_KEY is not configured: cannot obtain a service token");
        }

        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri("/auth/token")
                    .header("X-API-Key", apiKey)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException ex) {
            log.warn("Failed to obtain a service token from auth-service", ex);
            throw ApiException.upstream("auth-service unreachable or API key rejected");
        }
        if (body == null || !(body.get("access_token") instanceof String accessToken) || accessToken.isBlank()) {
            throw ApiException.upstream("auth-service returned no access token");
        }

        long ttl = body.get("expires_in") instanceof Number n ? n.longValue() : DEFAULT_TTL_SECONDS;
        this.cachedToken = accessToken;
        this.expiresAt = Instant.now().plusSeconds(ttl);
        return accessToken;
    }
}
