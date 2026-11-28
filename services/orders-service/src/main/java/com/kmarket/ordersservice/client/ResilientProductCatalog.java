
package com.kmarket.ordersservice.client;

import com.kmarket.ordersservice.catalog.CatalogProduct;
import com.kmarket.ordersservice.catalog.ProductCatalog;
import com.kmarket.ordersservice.exception.ApiException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.http.HttpStatus;
import io.github.resilience4j.retry.Retry;

import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resilience4j decorator for Catalog reads.
 *
 * Applies:
 * - Bulkhead: limits concurrent requests.
 * - Circuit breaker: prevents repeated calls to a failing Catalog.
 * - Retry: retries transient failures.
 *
 * Business 4xx errors are not retried.
 */
public class ResilientProductCatalog implements ProductCatalog {

    private final ProductCatalog delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;

    public ResilientProductCatalog(
            ProductCatalog delegate,
            CircuitBreaker circuitBreaker,
            Retry retry,
            Bulkhead bulkhead) {

        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.bulkhead = bulkhead;
    }

    /**
     * Returns true for transient failures such as HTTP 5xx,
     * connection errors and timeouts.
     *
     * Business HTTP 4xx errors must not be retried.
     */
    public static boolean isTransient(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            return false;
        }

        if (t instanceof ApiException api) {
            HttpStatus status = api.getStatus();
            return status == null || status.is5xxServerError();
        }

        // Non-API exceptions are considered retryable.
        // Narrow this to transport exceptions if needed.
        return true;
    }

    @Override
    public Map<Integer, CatalogProduct> fetchByIds(
            Collection<Integer> productIds) {

        Supplier<Map<Integer, CatalogProduct>> call =
                () -> delegate.fetchByIds(productIds);

        try {
            Supplier<Map<Integer, CatalogProduct>> decorated =
                    Bulkhead.decorateSupplier(bulkhead, call);

            decorated = Retry.decorateSupplier(retry, decorated);

            decorated = CircuitBreaker.decorateSupplier(
                    circuitBreaker, decorated
            );

            return decorated.get();
        } catch (CallNotPermittedException ex) {
            throw ApiException.upstream(
                    "Catalog circuit breaker is open"
            );
        }
    }
}
