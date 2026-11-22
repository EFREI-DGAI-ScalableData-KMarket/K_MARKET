package com.kmarket.ordersservice.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link OrderStatus} parsing and transition rules. */
class OrderStatusTest {

    @Test
    void fromValue_isCaseInsensitiveAndTrimmed() {
        assertThat(OrderStatus.fromValue("  Paid ")).isEqualTo(OrderStatus.PAID);
        assertThat(OrderStatus.fromValue("stock_reserved")).isEqualTo(OrderStatus.STOCK_RESERVED);
    }

    @Test
    void fromValue_unknownOrNull_throws() {
        assertThatThrownBy(() -> OrderStatus.fromValue("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderStatus.fromValue(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canTransitionTo_allowsForwardFlow() {
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.STOCK_RESERVED)).isTrue();
        assertThat(OrderStatus.STOCK_RESERVED.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PREPARING)).isTrue();
        assertThat(OrderStatus.PREPARING.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED)).isTrue();
    }

    @Test
    void canTransitionTo_sameStatusIsAllowed() {
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PAID)).isTrue();
    }

    @Test
    void canTransitionTo_rejectsBackwardAndTerminalTransitions() {
        assertThat(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.PENDING)).isFalse();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.PENDING)).isFalse();
        assertThat(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.PAYMENT_FAILED.canTransitionTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
    }
}
