package com.kmarket.ordersservice.service;

/**
 * Resolves the human-readable name of a product for display.
 *
 * <p>In the full system this is answered by the Catalog service (the {@code OrderDetail}
 * schema notes {@code product_name} is "Récupéré via l'API Catalogue"). That HTTP
 * integration is not wired yet, so this is an explicit, injectable seam mirroring
 * {@link ProductPricer}: swap the default implementation for a Catalog-backed one
 * without touching {@link OrderService}.</p>
 */
public interface ProductNameResolver {

    /**
     * @param productId the catalog product id
     * @return a display name for the product, never {@code null}
     */
    String productName(int productId);
}
