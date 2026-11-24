package com.kmarket.ordersservice;

import com.kmarket.ordersservice.catalog.CatalogProduct;
import com.kmarket.ordersservice.catalog.ProductCatalog;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic in-memory {@link ProductCatalog} for {@code @SpringBootTest} integration
 * tests, so order logic (persistence, idempotency, outbox, transactions) can be exercised
 * against a real database without a live Catalog service.
 *
 * <p>Registered as a {@code @Bean}, it takes precedence over the production
 * {@code CatalogConfig} bean (which is {@code @ConditionalOnMissingBean}). Every positive
 * product id resolves to an <em>active</em> product with a stable, strictly positive price.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestCatalogConfiguration {

    @Bean
    ProductCatalog testProductCatalog() {
        return (Collection<Integer> productIds) -> {
            Map<Integer, CatalogProduct> result = new LinkedHashMap<>();
            for (Integer id : productIds) {
                if (id != null && id > 0) {
                    int priceCents = 500 + Math.floorMod(id * 37, 10_000);
                    result.put(id, new CatalogProduct(id, "Product #" + id, priceCents, true));
                }
            }
            return result;
        };
    }
}
