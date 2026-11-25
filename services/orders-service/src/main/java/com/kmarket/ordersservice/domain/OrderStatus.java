package com.kmarket.ordersservice.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * The order lifecycle statuses of the contract ({@code OrderStatus} schema) together
 * with the allowed transitions between them.
 *
 * <p>Persisted as the lower-case {@code snake_case} string stored in
 * {@code orders.orders.status}; use {@link #fromValue(String)} to parse an incoming
 * value and {@link #value()} to render it back.</p>
 *
 * <p>The transition map encodes the Saga / logistics flow:
 * {@code pending → stock_reserved → paid → preparing → shipped → delivered}, with
 * {@code rejected} reached from {@code pending} when stock cannot be reserved (no payment
 * attempted), {@code payment_failed} reachable from the early stages and {@code cancelled}
 * reachable up to {@code paid}. Terminal states ({@code delivered}, {@code payment_failed},
 * {@code cancelled}, {@code rejected}) allow no further transition, so e.g.
 * {@code delivered → pending} is rejected as a {@code 409} by {@link #canTransitionTo(OrderStatus)}.</p>
 */
public enum OrderStatus {

    PENDING("pending"),
    STOCK_RESERVED("stock_reserved"),
    PAID("paid"),
    PREPARING("preparing"),
    SHIPPED("shipped"),
    DELIVERED("delivered"),
    PAYMENT_FAILED("payment_failed"),
    CANCELLED("cancelled"),
    REJECTED("rejected");

    private final String value;

    OrderStatus(String value) {
        this.value = value;
    }

    /** The wire/DB representation (e.g. {@code "stock_reserved"}). */
    public String value() {
        return value;
    }

    /**
     * Parses a contract {@code OrderStatus} value.
     *
     * @param raw the wire value (case-insensitive)
     * @return the matching enum constant
     * @throws IllegalArgumentException if {@code raw} is {@code null} or not a known status
     */
    public static OrderStatus fromValue(String raw) {
        if (raw != null) {
            for (OrderStatus status : values()) {
                if (status.value.equalsIgnoreCase(raw.trim())) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("Unknown order status: " + raw);
    }

    /**
     * Allowed next statuses for each status. Statuses absent from the key set (the
     * terminal ones) map to an empty set and thus accept no transition.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING, Set.of(STOCK_RESERVED, PAYMENT_FAILED, CANCELLED, REJECTED),
            STOCK_RESERVED, Set.of(PAID, PAYMENT_FAILED, CANCELLED),
            PAID, Set.of(PREPARING, CANCELLED),
            PREPARING, Set.of(SHIPPED),
            SHIPPED, Set.of(DELIVERED));

    /**
     * Whether moving from {@code this} status to {@code target} is a legal transition.
     * Moving to the same status is treated as a no-op and allowed.
     */
    public boolean canTransitionTo(OrderStatus target) {
        if (this == target) {
            return true;
        }
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /** The comma-separated list of known values, for error messages. */
    public static String knownValues() {
        return Arrays.stream(values()).map(OrderStatus::value).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
