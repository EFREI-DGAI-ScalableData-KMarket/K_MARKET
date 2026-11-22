package com.kmarket.ordersservice.service;

/**
 * Local stand-in for the Catalog naming service: returns a deterministic, stable
 * display name for any product id so that {@code GET /orders/{order_id}} returns a
 * fully populated {@code OrderDetail} before the Catalog HTTP client is wired.
 *
 * <p>Registered via {@link com.kmarket.ordersservice.config.PricingConfig} with
 * {@code @ConditionalOnMissingBean}, so a real Catalog-backed {@link ProductNameResolver}
 * bean, once added, automatically takes precedence.</p>
 */
public class DefaultProductNameResolver implements ProductNameResolver {

    @Override
    public String productName(int productId) {
        return "Product #" + productId;
    }
}
