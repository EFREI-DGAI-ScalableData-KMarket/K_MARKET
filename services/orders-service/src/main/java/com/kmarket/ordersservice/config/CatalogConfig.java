package com.kmarket.ordersservice.config;

import com.kmarket.ordersservice.catalog.CatalogGraphQlClient;
import com.kmarket.ordersservice.catalog.ProductCatalog;
import com.kmarket.ordersservice.catalog.UnconfiguredProductCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@link ProductCatalog} seam.
 *
 * <p>When {@code catalog.base-url} is set (e.g. {@code http://catalog-service:8004/v1}
 * in Docker), the real {@link CatalogGraphQlClient} validates and prices orders against
 * the live Catalog. When it is not set, the service still starts but
 * {@link UnconfiguredProductCatalog} makes {@code POST /orders} fail with a
 * {@code 502 UPSTREAM_ERROR} — there is no local pricing fallback.</p>
 */
@Configuration
public class CatalogConfig {

    private static final Logger log = LoggerFactory.getLogger(CatalogConfig.class);

    @Bean
    @ConditionalOnMissingBean(ProductCatalog.class)
    public ProductCatalog productCatalog(ObjectProvider<RestClient.Builder> builder, Environment env) {
        String baseUrl = env.getProperty("catalog.base-url", "").trim();
        if (baseUrl.isEmpty()) {
            log.warn("catalog.base-url is not set: POST /orders will fail with 502 until "
                    + "CATALOG_BASE_URL is configured and the Catalog service is reachable.");
            return new UnconfiguredProductCatalog();
        }
        RestClient.Builder restClientBuilder = builder.getIfAvailable(RestClient::builder);
        return new CatalogGraphQlClient(restClientBuilder, baseUrl + "/catalog");
    }
}
