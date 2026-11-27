package com.kmarket.ordersservice.client;

import com.kmarket.ordersservice.entity.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@link StockClient} used when no Stock base URL is configured.
 *
 * <p>Returns {@link StockReservationOutcome#UNCERTAIN} so an order is never silently
 * reserved nor falsely rejected for "insufficient stock" when Stock is simply not wired:
 * the order stays {@code pending} and is surfaced as a retryable/reconcilable outcome.</p>
 */
public class UnconfiguredStockClient implements StockClient {

    private static final Logger log = LoggerFactory.getLogger(UnconfiguredStockClient.class);

    @Override
    public StockReservationOutcome reserve(int orderId, List<OrderItem> lines) {
        log.warn("Stock service is not configured (set STOCK_BASE_URL): cannot reserve order {}", orderId);
        return StockReservationOutcome.UNCERTAIN;
    }

    @Override
    public StockOperationOutcome commit(int orderId) {
        log.warn("Stock service is not configured (set STOCK_BASE_URL): cannot commit order {}", orderId);
        return StockOperationOutcome.UNCERTAIN;
    }

    @Override
    public StockOperationOutcome release(int orderId) {
        log.warn("Stock service is not configured (set STOCK_BASE_URL): cannot release order {}", orderId);
        return StockOperationOutcome.UNCERTAIN;
    }
}
