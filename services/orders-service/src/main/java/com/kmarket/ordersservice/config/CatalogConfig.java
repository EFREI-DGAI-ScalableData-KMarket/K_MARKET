package com.kmarket.ordersservice.config;

import com.kmarket.ordersservice.catalog.CatalogGraphQlClient;
import com.kmarket.ordersservice.catalog.ProductCatalog;
import com.kmarket.ordersservice.catalog.UnconfiguredProductCatalog;
import com.kmarket.ordersservice.client.ServiceTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Wires the {@link ProductCatalog} seam.
 *
 * <p>When {@code catalog.base-url} is set (e.g. {@code http://catalog-service:8004/v1}
 * in Docker), the real {@link CatalogGraphQlClient} validates and prices orders against
 * the live Catalog. When it is not set, the service still starts but
 * {@link UnconfiguredProductCatalog} makes {@code POST /orders} fail with a
 * {@code 502 UPSTREAM_ERROR} — there is no local pricing fallback.</p>
 *
 * <p>The HTTP client is pinned to <b>HTTP/1.1</b>. The default JDK {@code HttpClient} used
 * by {@code RestClient} negotiates HTTP/2 and, over plaintext, emits an h2c upgrade
 * ({@code Connection: Upgrade}) that the Catalog's uvicorn/ASGI server rejects with
 * {@code 400 "Invalid HTTP request received"} ("Unsupported upgrade request"), which would
 * otherwise surface as a misleading {@code 502 Catalog service unreachable}.</p>
 */
@Configuration
public class CatalogConfig {

    private static final Logger log = LoggerFactory.getLogger(CatalogConfig.class);

    /** Short timeouts so a hung Catalog call fails fast with a clean 502 (same spirit as Stock/Payment). */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    @ConditionalOnMissingBean(ProductCatalog.class)
    public ProductCatalog productCatalog(ObjectProvider<RestClient.Builder> builder, Environment env) {
        String baseUrl = env.getProperty("catalog.base-url", "").trim();
        if (baseUrl.isEmpty()) {
            log.warn("catalog.base-url is not set: POST /orders will fail with 502 until "
                    + "CATALOG_BASE_URL is configured and the Catalog service is reachable.");
            return new UnconfiguredProductCatalog();
        }

        // Force HTTP/1.1 so no h2c upgrade header is sent to the Catalog's uvicorn server.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        RestClient.Builder restClientBuilder = builder.getIfAvailable(RestClient::builder)
                .clone()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient));

        // Catalog is called with the orders service_account identity (decision D6), obtained
        // by exchanging ORDERS_API_KEY at auth-service — the same mechanism as Stock/Payment.
        String authBaseUrl = env.getProperty("auth.base-url", "").trim();
        String apiKey = env.getProperty("orders.api-key", "").trim();
        ServiceTokenProvider tokenProvider = new ServiceTokenProvider(restClientBuilder, authBaseUrl, apiKey);

        return new CatalogGraphQlClient(restClientBuilder, baseUrl + "/catalog", tokenProvider);
    }
}
