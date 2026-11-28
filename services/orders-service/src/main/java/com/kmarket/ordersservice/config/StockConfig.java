package com.kmarket.ordersservice.config;

import com.kmarket.ordersservice.client.ResilientStockClient;
import com.kmarket.ordersservice.client.ServiceTokenProvider;
import com.kmarket.ordersservice.client.StockClient;
import com.kmarket.ordersservice.client.StockRestClient;
import com.kmarket.ordersservice.client.UnconfiguredStockClient;
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
 * Wires the {@link StockClient} seam (Saga stock reservation) and the
 * {@link ServiceTokenProvider} that authenticates the orders service to its peers.
 *
 * <p>Enabled by configuration: {@code stock.base-url} (Stock service), {@code auth.base-url}
 * (auth-service) and {@code orders.api-key} (the orders technical API key). When Stock is
 * not configured, {@link UnconfiguredStockClient} keeps orders {@code pending} instead of
 * reserving or rejecting.</p>
 */
@Configuration
public class StockConfig {

    private static final Logger log = LoggerFactory.getLogger(StockConfig.class);

    /** Short timeout so a hung Stock call becomes an UNCERTAIN outcome quickly. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    @ConditionalOnMissingBean(StockClient.class)
    public StockClient stockClient(ObjectProvider<RestClient.Builder> builderProvider, Environment env,
                                   CircuitBreakerRegistry cbRegistry, BulkheadRegistry bulkheadRegistry) {
        String stockBaseUrl = env.getProperty("stock.base-url", "").trim();
        if (stockBaseUrl.isEmpty()) {
            log.warn("stock.base-url is not set: orders stay 'pending' (stock cannot be reserved). "
                    + "Set STOCK_BASE_URL to enable the Saga reservation step.");
            return new UnconfiguredStockClient();
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
        StockClient real = new StockRestClient(base, stockBaseUrl, tokenProvider);
        // Circuit breaker + bulkhead, NO retry (mutations: recovery is the idempotent Saga re-run).
        return new ResilientStockClient(real, cbRegistry.circuitBreaker("stock"),
                bulkheadRegistry.bulkhead("stock"));
    }
}
