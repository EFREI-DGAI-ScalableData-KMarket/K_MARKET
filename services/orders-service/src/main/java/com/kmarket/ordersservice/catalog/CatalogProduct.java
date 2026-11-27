package com.kmarket.ordersservice.catalog;

/**
 * The subset of a Catalog {@code Product} that orders-service needs to validate a line
 * and freeze its price: the id, the display name, the unit price in cents and whether
 * the product is still sellable (an archived / {@code inactive} product must not be
 * ordered).
 */
public record CatalogProduct(
        int productId,
        String name,
        int priceCents,
        boolean active) {
}
