package com.kmarket.ordersservice.service;

/**
 * Local stand-in for the Catalog pricing service: returns a deterministic,
 * strictly positive unit price for any product id so that {@code POST /orders}
 * is fully functional before the Catalog HTTP client is wired.
 *
 * <p>Registered via {@link com.kmarket.ordersservice.config.PricingConfig} with
 * {@code @ConditionalOnMissingBean}, so a real Catalog-backed {@link ProductPricer}
 * bean, once added, automatically takes precedence.</p>
 */
public class DefaultProductPricer implements ProductPricer {

    /** Base price so every product costs at least a few euros. */
    private static final int BASE_CENTS = 500;

    @Override
    public Integer unitPriceCents(int productId) {
        if (productId <= 0) {
            return null;
        }
        // Deterministic 5.00–104.99 € range, stable per product id.
        return BASE_CENTS + Math.floorMod(productId * 37, 10_000);
    }
}

