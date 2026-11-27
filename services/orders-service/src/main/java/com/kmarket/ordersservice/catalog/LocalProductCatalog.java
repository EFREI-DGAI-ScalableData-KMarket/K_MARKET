package com.kmarket.ordersservice.catalog;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Offline fallback for {@link ProductCatalog}, used when no Catalog base URL is configured
 * (e.g. local runs and tests). Returns a deterministic, strictly positive price and a
 * stable name for any positive product id, so {@code POST /orders} and
 * {@code GET /orders/{id}} are fully functional before the Catalog HTTP client is wired.
 *
 * <p>The pricing/naming math mirrors the previous {@code DefaultProductPricer} /
 * {@code DefaultProductNameResolver} so existing behaviour and tests are unchanged.</p>
 */
public class LocalProductCatalog implements ProductCatalog {

    /** Base price so every product costs at least a few euros. */
    private static final int BASE_CENTS = 500;

    @Override
    public Map<Integer, CatalogProduct> fetchByIds(Collection<Integer> productIds) {
        Map<Integer, CatalogProduct> result = new LinkedHashMap<>();
        for (Integer id : productIds) {
            if (id != null && id > 0) {
                result.put(id, new CatalogProduct(
                        id,
                        "Product #" + id,
                        BASE_CENTS + Math.floorMod(id * 37, 10_000),
                        true));
            }
        }
        return result;
    }
}
