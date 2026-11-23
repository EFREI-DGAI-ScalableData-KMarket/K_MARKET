package com.kmarket.ordersservice.service;

/**
 * Resolves the unit price (in cents) of a product at the moment an order is placed.
 *
 * <p>In the full Saga this is answered by the Catalog service (step 2 of the
 * contract: "valider les produits et récupérer les prix"). That HTTP integration
 * is not wired yet, so this is an explicit, injectable seam: swap the default
 * implementation for a Catalog-backed one without touching {@link OrderService}.</p>
 */
public interface ProductPricer {

    /**
     * @param productId the catalog product id
     * @return the unit price in cents, or {@code null} if the product is unknown/unavailable
     */
    Integer unitPriceCents(int productId);
}
