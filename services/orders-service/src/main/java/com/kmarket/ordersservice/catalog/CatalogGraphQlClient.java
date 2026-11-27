package com.kmarket.ordersservice.catalog;

import com.kmarket.ordersservice.client.ServiceTokenProvider;
import com.kmarket.ordersservice.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real {@link ProductCatalog} backed by the Catalog GraphQL service.
 *
 * <p>Issues the contract's {@code productsByIds(ids: [Int!]!)} query in a single
 * {@code POST /catalog} call. The call authenticates with the orders
 * {@code service_account} identity obtained from {@link ServiceTokenProvider} (decision
 * D6: a service calls its peers with its own technical token via its API key, never by
 * forwarding the end user's JWT). Unknown ids are omitted by Catalog and therefore absent
 * from the returned map; an unreachable or erroring Catalog surfaces as a {@code 502}
 * ({@code UPSTREAM_ERROR}) so the order fails cleanly instead of pricing against stale data.</p>
 */
public class CatalogGraphQlClient implements ProductCatalog {

    private static final Logger log = LoggerFactory.getLogger(CatalogGraphQlClient.class);

    /** Catalog honours at most 200 ids per {@code productsByIds} call. */
    private static final int MAX_IDS = 200;

    private static final String QUERY = """
            query ($ids: [Int!]!) {
              productsByIds(ids: $ids) {
                product_id
                name
                price_cents
                status
              }
            }""";

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;

    public CatalogGraphQlClient(RestClient.Builder builder, String baseUrl, ServiceTokenProvider tokenProvider) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<Integer, CatalogProduct> fetchByIds(Collection<Integer> productIds) {
        List<Integer> ids = productIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        if (ids.size() > MAX_IDS) {
            throw ApiException.badRequest("An order cannot reference more than " + MAX_IDS + " distinct products");
        }

        Map<String, Object> body = Map.of(
                "query", QUERY,
                "variables", Map.of("ids", ids));

        String token;
        try {
            token = tokenProvider.getToken();
        } catch (RuntimeException ex) {
            // Could not obtain a service token (auth-service down / API key rejected).
            log.warn("Catalog call aborted: no service token", ex);
            throw ApiException.upstream("Catalog service unreachable");
        }

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException ex) {
            log.warn("Catalog service call failed", ex);
            throw ApiException.upstream("Catalog service unreachable");
        }

        if (response == null) {
            throw ApiException.upstream("Empty response from Catalog service");
        }
        if (response.get("errors") instanceof List<?> errors && !errors.isEmpty()) {
            log.warn("Catalog GraphQL returned errors: {}", errors);
            throw ApiException.upstream("Catalog service returned an error");
        }

        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            throw ApiException.upstream("Malformed response from Catalog service");
        }
        Object list = dataMap.get("productsByIds");
        if (!(list instanceof List<?> products)) {
            return Map.of();
        }

        Map<Integer, CatalogProduct> result = new LinkedHashMap<>();
        for (Object element : products) {
            if (element instanceof Map<?, ?> product) {
                CatalogProduct mapped = toProduct(product);
                if (mapped != null) {
                    result.put(mapped.productId(), mapped);
                }
            }
        }
        return result;
    }

    private CatalogProduct toProduct(Map<?, ?> product) {
        Object id = product.get("product_id");
        Object price = product.get("price_cents");
        if (!(id instanceof Number idNum) || !(price instanceof Number priceNum)) {
            return null;
        }
        String name = product.get("name") instanceof String n ? n : "Product #" + idNum.intValue();
        boolean active = !"inactive".equals(product.get("status"));
        return new CatalogProduct(idNum.intValue(), name, priceNum.intValue(), active);
    }
}
