package com.kmarket.ordersservice.client;

import com.kmarket.ordersservice.entity.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real {@link StockClient} backed by the Stock service REST API ({@code POST /stock/reserve}).
 *
 * <p>Authenticates with a {@code service_account} JWT from {@link ServiceTokenProvider}
 * (the reserve endpoint is restricted to {@code service_account}/{@code admin}). The call
 * is classified into a {@link StockReservationOutcome}:</p>
 * <ul>
 *   <li>HTTP 201 → {@link StockReservationOutcome#RESERVED} (also the idempotent "already
 *       reserved" case).</li>
 *   <li>HTTP 422 → {@link StockReservationOutcome#INSUFFICIENT} (nothing reserved).</li>
 *   <li>timeout / transport error / any other status → {@link StockReservationOutcome#UNCERTAIN}:
 *       the reservation may or may not have been applied, so the order stays pending for
 *       reconciliation rather than being treated as insufficient.</li>
 * </ul>
 */
public class StockRestClient implements StockClient {

    private static final Logger log = LoggerFactory.getLogger(StockRestClient.class);

    /** Cap how much of an error body we log, so a large/hostile response can't flood the logs. */
    private static final int MAX_BODY_LOG_CHARS = 500;

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;
    /** Kept for diagnostics only (logged on failures); the RestClient already has it as its base URL. */
    private final String baseUrl;

    public StockRestClient(RestClient.Builder builder, String baseUrl, ServiceTokenProvider tokenProvider) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.baseUrl = baseUrl;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public StockReservationOutcome reserve(int orderId, List<OrderItem> lines) {
        // Merge duplicate product lines into a single quantity per product (one reservation
        // per product, as the Stock contract expects).
        Map<Integer, Integer> qtyByProduct = new LinkedHashMap<>();
        for (OrderItem line : lines) {
            qtyByProduct.merge(line.getProductId(), line.getQuantity(), Integer::sum);
        }
        List<Map<String, Object>> items = qtyByProduct.entrySet().stream()
                .map(e -> Map.<String, Object>of("product_id", e.getKey(), "quantity", e.getValue()))
                .toList();

        Map<String, Object> body = Map.of("order_id", orderId, "items", items);

        String token;
        try {
            token = tokenProvider.getToken();
        } catch (RuntimeException ex) {
            // Could not even authenticate: we never sent the reserve, so nothing was applied.
            // Still treat as UNCERTAIN so the order is reconciled rather than silently rejected.
            log.warn("Stock reserve for order {} aborted: no service token", orderId, ex);
            return StockReservationOutcome.UNCERTAIN;
        }

        try {
            long startNanos = System.nanoTime();
            return restClient.post()
                    .uri("/stock/reserve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .body(body)
                    .exchange((request, response) -> {
                        int code = response.getStatusCode().value();
                        HttpStatus status = HttpStatus.resolve(code);
                        long ms = (System.nanoTime() - startNanos) / 1_000_000;
                        if (status == HttpStatus.CREATED) {
                            log.debug("Stock reserve order={} -> RESERVED (HTTP 201, {} ms)", orderId, ms);
                            return StockReservationOutcome.RESERVED;
                        }
                        if (status == HttpStatus.UNPROCESSABLE_CONTENT) {
                            // Definitive business outcome: nothing reserved.
                            log.info("Stock reserve order={} -> INSUFFICIENT (HTTP 422, {} ms): {}",
                                    orderId, ms, readBody(response));
                            return StockReservationOutcome.INSUFFICIENT;
                        }
                        // 401/403/404/409/5xx etc.: a TECHNICAL error, not "insufficient". We do not
                        // know whether stock moved, so the order stays pending for reconciliation.
                        // Log enough to diagnose (status + sanitized body) without leaking the token.
                        log.warn("Stock reserve order={} -> UNCERTAIN: unexpected HTTP {} ({} ms) POST {}/stock/reserve; body={}",
                                orderId, code, ms, baseUrl, readBody(response));
                        return StockReservationOutcome.UNCERTAIN;
                    });
        } catch (Exception ex) {
            // Connect/read timeout or any transport failure: the request may have been
            // processed by Stock or not — uncertain, needs reconciliation (NOT insufficient).
            log.warn("Stock reserve order={} -> UNCERTAIN: {} calling POST {}/stock/reserve: {}",
                    orderId, ex.getClass().getSimpleName(), baseUrl, ex.getMessage(), ex);
            return StockReservationOutcome.UNCERTAIN;
        }
    }

    @Override
    public StockOperationOutcome commit(int orderId) {
        return orderScopedOperation("/stock/commit", orderId, "commit");
    }

    @Override
    public StockOperationOutcome release(int orderId) {
        return orderScopedOperation("/stock/release", orderId, "release");
    }

    /**
     * Shared body for {@code POST /stock/commit} and {@code POST /stock/release}: both take an
     * {@code {order_id}} body and are idempotent. Classifies the response into a
     * {@link StockOperationOutcome} (2xx → DONE, 404 → NOT_FOUND, anything else/transport →
     * UNCERTAIN) so the Saga never assumes a timeout meant the operation failed.
     */
    private StockOperationOutcome orderScopedOperation(String uri, int orderId, String action) {
        String token;
        try {
            token = tokenProvider.getToken();
        } catch (RuntimeException ex) {
            log.warn("Stock {} for order {} aborted: no service token", action, orderId, ex);
            return StockOperationOutcome.UNCERTAIN;
        }

        Map<String, Object> body = Map.of("order_id", orderId);
        try {
            long startNanos = System.nanoTime();
            return restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .body(body)
                    .exchange((request, response) -> {
                        int code = response.getStatusCode().value();
                        HttpStatus status = HttpStatus.resolve(code);
                        long ms = (System.nanoTime() - startNanos) / 1_000_000;
                        if (status != null && status.is2xxSuccessful()) {
                            log.debug("Stock {} order={} -> DONE (HTTP {}, {} ms)", action, orderId, code, ms);
                            return StockOperationOutcome.DONE;
                        }
                        if (status == HttpStatus.NOT_FOUND) {
                            log.info("Stock {} order={} -> NOT_FOUND (HTTP 404, {} ms)", action, orderId, ms);
                            return StockOperationOutcome.NOT_FOUND;
                        }
                        log.warn("Stock {} order={} -> UNCERTAIN: unexpected HTTP {} ({} ms) POST {}{}; body={}",
                                action, orderId, code, ms, baseUrl, uri, readBody(response));
                        return StockOperationOutcome.UNCERTAIN;
                    });
        } catch (Exception ex) {
            log.warn("Stock {} order={} -> UNCERTAIN: {} calling POST {}{}: {}",
                    action, orderId, ex.getClass().getSimpleName(), baseUrl, uri, ex.getMessage(), ex);
            return StockOperationOutcome.UNCERTAIN;
        }
    }

    /**
     * Reads a (failed) response body for diagnostics: sanitized to a single line and capped at
     * {@link #MAX_BODY_LOG_CHARS}. The Stock contract's error bodies are the {@code Error} schema
     * ({@code code}/{@code message}/{@code request_id}) and carry no secrets. Never throws.
     */
    private static String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try {
            byte[] bytes = response.getBody().readAllBytes();
            if (bytes.length == 0) {
                return "<empty>";
            }
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").trim();
            return text.length() > MAX_BODY_LOG_CHARS ? text.substring(0, MAX_BODY_LOG_CHARS) + "…" : text;
        } catch (Exception ex) {
            return "<unreadable: " + ex.getClass().getSimpleName() + ">";
        }
    }
}
