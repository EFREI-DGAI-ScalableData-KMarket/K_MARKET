package com.kmarket.ordersservice.catalog;

import java.util.Collection;
import java.util.Map;

/**
 * Looks products up in the Catalog service (step 2 of the {@code POST /orders} Saga:
 * "valider les produits et récupérer les prix").
 *
 * <p>Batch by design: an order is priced with a <em>single</em> Catalog call (the GraphQL
 * {@code productsByIds} query), not one call per line. The returned map is keyed by
 * {@code product_id}; ids that the Catalog does not know are simply absent from the map
 * (as the contract specifies: "les ids inconnus sont ignorés").</p>
 *
 * <p>This is an injectable seam: {@link CatalogGraphQlClient} is the real HTTP
 * implementation, and {@link UnconfiguredProductCatalog} fails fast with a 502 when no
 * Catalog base URL is configured (there is no local pricing fallback).</p>
 */
public interface ProductCatalog {

    /**
     * Fetches the given products in one call.
     *
     * @param productIds the product ids to resolve (deduplicated by the implementation)
     * @return a map {@code product_id -> product} containing only the ids that exist
     */
    Map<Integer, CatalogProduct> fetchByIds(Collection<Integer> productIds);
}
