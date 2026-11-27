package com.kmarket.ordersservice.client;

/**
 * Result of a Saga stock finalization ({@code POST /stock/commit}) or compensation
 * ({@code POST /stock/release}).
 *
 * <ul>
 *   <li>{@link #DONE} — the operation succeeded (or was already applied: both endpoints are
 *       idempotent). The stock is committed/released as intended.</li>
 *   <li>{@link #NOT_FOUND} — the Stock service has no reservation for the order (HTTP 404).
 *       For a release this means there is nothing to compensate (already clean). For a
 *       commit it is a definitive problem that must be reconciled.</li>
 *   <li>{@link #UNCERTAIN} — the call timed out, the response was lost, or an ambiguous
 *       status was returned (e.g. 409 on commit after a release, 5xx, transport error). The
 *       operation may or may not have been applied: the Saga must NOT assume it failed and
 *       must reconcile the reservation status before completing.</li>
 * </ul>
 */
public enum StockOperationOutcome {
    DONE,
    NOT_FOUND,
    UNCERTAIN
}
