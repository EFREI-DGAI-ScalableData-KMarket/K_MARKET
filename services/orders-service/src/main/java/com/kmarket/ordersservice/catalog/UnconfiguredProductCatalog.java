package com.kmarket.ordersservice.catalog;

import com.kmarket.ordersservice.exception.ApiException;

import java.util.Collection;
import java.util.Map;

/**
 * {@link ProductCatalog} used when no Catalog base URL is configured.
 *
 * <p>The service still starts (so health checks, listing and reads work), but any
 * operation that needs product data — chiefly {@code POST /orders} — fails fast with a
 * {@code 502 UPSTREAM_ERROR}. There is no local pricing fallback: orders must be validated
 * and priced against the real Catalog.</p>
 */
public class UnconfiguredProductCatalog implements ProductCatalog {

    @Override
    public Map<Integer, CatalogProduct> fetchByIds(Collection<Integer> productIds) {
        throw ApiException.upstream("Catalog service is not configured (set CATALOG_BASE_URL)");
    }
}
