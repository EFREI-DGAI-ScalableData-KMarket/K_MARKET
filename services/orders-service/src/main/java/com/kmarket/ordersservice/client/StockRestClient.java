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

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;

    public StockRestClient(RestClient.Builder builder, String baseUrl, ServiceTokenProvider tokenProvider) {
        this.restClient = builder.baseUrl(baseUrl).build();
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
            return restClient.post()
                    .uri("/stock/reserve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .body(body)
                    .exchange((request, response) -> {
                        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
                        if (status == HttpStatus.CREATED) {
                            return StockReservationOutcome.RESERVED;
                        }
                        if (status == HttpStatus.UNPROCESSABLE_CONTENT) {
                            return StockReservationOutcome.INSUFFICIENT;
                        }
                        // 401/403/404/409/5xx etc.: outcome unknown w.r.t. whether stock moved.
                        log.warn("Stock reserve for order {} returned unexpected status {}",
                                orderId, response.getStatusCode());
                        return StockReservationOutcome.UNCERTAIN;
                    });
        } catch (Exception ex) {
            // Connect/read timeout or any transport failure: the request may have been
            // processed by Stock or not — uncertain, needs reconciliation (NOT insufficient).
            log.warn("Stock reserve for order {} failed (treated as UNCERTAIN)", orderId, ex);
            return StockReservationOutcome.UNCERTAIN;
        }
    }
}
