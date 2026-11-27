package com.kmarket.ordersservice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Real {@link PaymentClient} backed by the Payment service REST API
 * ({@code POST /payments}, {@code GET /payments/{order_id}}).
 *
 * <p>Authenticates with a {@code service_account} JWT from {@link ServiceTokenProvider}
 * (the payment endpoints are restricted to {@code service_account}/{@code admin}). The
 * capture result is classified into a {@link PaymentOutcome}:</p>
 * <ul>
 *   <li>HTTP 201 → {@link PaymentOutcome#SUCCEEDED} (first capture).</li>
 *   <li>HTTP 409 → a payment already exists for this idempotency key (a safe retry after a
 *       lost response). The real outcome is recovered with {@link #lookup(int)} rather than
 *       charging again.</li>
 *   <li>HTTP 422 → {@link PaymentOutcome#DECLINED} (definitive business decline).</li>
 *   <li>timeout / transport error / any other status → {@link PaymentOutcome#UNCERTAIN}:
 *       the payment may or may not have been applied, so the order stays recoverable
 *       rather than being treated as failed.</li>
 * </ul>
 *
 * <p>The same request fields and status codes as the Payment contract are used; the
 * currency is always {@code EUR} (the only contract-allowed value).</p>
 */
public class PaymentRestClient implements PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentRestClient.class);

    private static final String CURRENCY_EUR = "EUR";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_REFUNDED = "refunded";
    private static final String STATUS_FAILED = "failed";

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;

    public PaymentRestClient(RestClient.Builder builder, String baseUrl, ServiceTokenProvider tokenProvider) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
    }

    @Override
    public PaymentOutcome capture(int orderId, long amountCents, String idempotencyKey) {
        String token;
        try {
            token = tokenProvider.getToken();
        } catch (RuntimeException ex) {
            // Could not even authenticate: nothing was charged. Uncertain so the order is
            // reconciled rather than silently failed.
            log.warn("Payment capture for order {} aborted: no service token", orderId, ex);
            return PaymentOutcome.UNCERTAIN;
        }

        // amount_cents is an integer in the contract; order totals are capped to Integer range.
        Map<String, Object> body = Map.of(
                "order_id", orderId,
                "amount_cents", (int) amountCents,
                "currency", CURRENCY_EUR);

        try {
            return restClient.post()
                    .uri("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(body)
                    .exchange((request, response) -> {
                        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
                        if (status == HttpStatus.CREATED) {
                            return PaymentOutcome.SUCCEEDED;
                        }
                        if (status == HttpStatus.CONFLICT) {
                            // Idempotency-Key already used (retry after a lost response).
                            // Recover the real outcome instead of charging again.
                            log.info("Payment capture for order {} got 409 (already exists); recovering via lookup",
                                    orderId);
                            return lookup(orderId);
                        }
                        if (status == HttpStatus.UNPROCESSABLE_CONTENT) {
                            return PaymentOutcome.DECLINED;
                        }
                        // 401/403/404/5xx etc.: outcome unknown w.r.t. whether the charge was applied.
                        log.warn("Payment capture for order {} returned unexpected status {}",
                                orderId, response.getStatusCode());
                        return PaymentOutcome.UNCERTAIN;
                    });
        } catch (Exception ex) {
            // Connect/read timeout or any transport failure: the request may or may not have
            // been processed by Payment — uncertain, needs reconciliation (NOT declined).
            log.warn("Payment capture for order {} failed (treated as UNCERTAIN)", orderId, ex);
            return PaymentOutcome.UNCERTAIN;
        }
    }

    @Override
    public PaymentOutcome lookup(int orderId) {
        String token;
        try {
            token = tokenProvider.getToken();
        } catch (RuntimeException ex) {
            log.warn("Payment lookup for order {} aborted: no service token", orderId, ex);
            return PaymentOutcome.UNCERTAIN;
        }

        try {
            return restClient.get()
                    .uri("/payments/{orderId}", orderId)
                    .header("Authorization", "Bearer " + token)
                    .exchange((request, response) -> {
                        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
                        if (status == HttpStatus.OK) {
                            return classify(readStatus(response.bodyTo(Map.class)), orderId);
                        }
                        if (status == HttpStatus.NOT_FOUND) {
                            // No payment recorded yet: the capture never reached the service.
                            return PaymentOutcome.UNCERTAIN;
                        }
                        log.warn("Payment lookup for order {} returned unexpected status {}",
                                orderId, response.getStatusCode());
                        return PaymentOutcome.UNCERTAIN;
                    });
        } catch (Exception ex) {
            log.warn("Payment lookup for order {} failed (treated as UNCERTAIN)", orderId, ex);
            return PaymentOutcome.UNCERTAIN;
        }
    }

    @Override
    public boolean refund(int orderId) {
        String token;
        try {
            token = tokenProvider.getToken();
        } catch (RuntimeException ex) {
            log.warn("Payment refund for order {} aborted: no service token", orderId, ex);
            return false;
        }

        try {
            return restClient.post()
                    .uri("/payments/{orderId}/refund", orderId)
                    .header("Authorization", "Bearer " + token)
                    .exchange((request, response) -> {
                        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
                        if (status != null && status.is2xxSuccessful()) {
                            return true;
                        }
                        if (status == HttpStatus.CONFLICT || status == HttpStatus.NOT_FOUND) {
                            // Already refunded / never payable: nothing more to compensate.
                            log.info("Payment refund for order {} returned {} (treated as already compensated)",
                                    orderId, response.getStatusCode());
                            return true;
                        }
                        log.warn("Payment refund for order {} returned unexpected status {}",
                                orderId, response.getStatusCode());
                        return false;
                    });
        } catch (Exception ex) {
            log.warn("Payment refund for order {} failed (uncertain, needs reconciliation)", orderId, ex);
            return false;
        }
    }

    private static String readStatus(Map<?, ?> body) {
        if (body == null) {
            return null;
        }
        Object status = body.get("status");
        return status instanceof String s ? s : null;
    }

    /** Maps a persisted payment {@code status} to an outcome. */
    private PaymentOutcome classify(String paymentStatus, int orderId) {
        if (STATUS_SUCCEEDED.equals(paymentStatus) || STATUS_REFUNDED.equals(paymentStatus)) {
            // 'refunded' implies a prior successful capture; treat the capture as succeeded.
            return PaymentOutcome.SUCCEEDED;
        }
        if (STATUS_FAILED.equals(paymentStatus)) {
            return PaymentOutcome.DECLINED;
        }
        // 'pending' or unknown: still uncertain.
        log.warn("Payment lookup for order {} returned non-terminal status '{}'", orderId, paymentStatus);
        return PaymentOutcome.UNCERTAIN;
    }
}
