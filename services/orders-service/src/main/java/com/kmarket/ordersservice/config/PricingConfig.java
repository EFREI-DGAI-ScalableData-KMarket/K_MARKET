package com.kmarket.ordersservice.config;

import com.kmarket.ordersservice.service.DefaultProductNameResolver;
import com.kmarket.ordersservice.service.DefaultProductPricer;
import com.kmarket.ordersservice.service.ProductNameResolver;
import com.kmarket.ordersservice.service.ProductPricer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Catalog seams ({@link ProductPricer} and {@link ProductNameResolver}).
 * Provides the local default implementations only when no other matching bean exists,
 * so adding Catalog-backed implementations later overrides them without any change here.
 */
@Configuration
public class PricingConfig {

    @Bean
    @ConditionalOnMissingBean(ProductPricer.class)
    public ProductPricer productPricer() {
        return new DefaultProductPricer();
    }

    @Bean
    @ConditionalOnMissingBean(ProductNameResolver.class)
    public ProductNameResolver productNameResolver() {
        return new DefaultProductNameResolver();
    }
}
