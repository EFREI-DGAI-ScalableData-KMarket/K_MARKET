package com.kmarket.ordersservice.config;

import com.kmarket.ordersservice.client.PaymentClient;
import com.kmarket.ordersservice.client.PaymentRestClient;
import com.kmarket.ordersservice.client.ResilientPaymentClient;
import com.kmarket.ordersservice.client.ServiceTokenProvider;
import com.kmarket.ordersservice.client.UnconfiguredPaymentClient;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Wires the {@link PaymentClient} seam (Saga payment capture, step 3) using the same
 * {@link ServiceTokenProvider} service-account authentication as {@link StockConfig}.
 *
 * <p>Enabled by configuration: {@code payment.base-url} (Payment service), {@code auth.base-url}
 * (auth-service) and {@code orders.api-key} (the orders technical API key). When Payment is
 * not configured, {@link UnconfiguredPaymentClient} keeps orders {@code stock_reserved}
 * (UNCERTAIN) instead of paying or failing, so Orders Service runs even when Payment is
 * temporarily unavailable.</p>
 */
@Configuration
public class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    /** Short timeouts so a hung Payment call becomes an UNCERTAIN outcome quickly. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    @ConditionalOnMissingBean(PaymentClient.class)
    public PaymentClient paymentClient(ObjectProvider<RestClient.Builder> builderProvider, Environment env,
                                       CircuitBreakerRegistry cbRegistry, BulkheadRegistry bulkheadRegistry) {
        String paymentBaseUrl = env.getProperty("payment.base-url", "").trim();
        if (paymentBaseUrl.isEmpty()) {
            log.warn("payment.base-url is not set: orders stay 'stock_reserved' (payment cannot be captured). "
                    + "Set PAYMENT_BASE_URL to enable the Saga payment step.");
            return new UnconfiguredPaymentClient();
        }

        String authBaseUrl = env.getProperty("auth.base-url", "").trim();
        String apiKey = env.getProperty("orders.api-key", "").trim();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) READ_TIMEOUT.toMillis());

        RestClient.Builder base = builderProvider.getIfAvailable(RestClient::builder)
                .clone()
                .requestFactory(requestFactory);

        ServiceTokenProvider tokenProvider = new ServiceTokenProvider(base, authBaseUrl, apiKey);
        PaymentClient real = new PaymentRestClient(base, paymentBaseUrl, tokenProvider);
        // Circuit breaker + bulkhead, NO retry (a payment is a mutation: never blindly retry a timeout).
        return new ResilientPaymentClient(real, cbRegistry.circuitBreaker("payment"),
                bulkheadRegistry.bulkhead("payment"));
    }
}
