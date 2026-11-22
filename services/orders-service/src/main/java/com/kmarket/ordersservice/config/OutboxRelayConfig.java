package com.kmarket.ordersservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduler so {@link com.kmarket.ordersservice.outbox.OutboxRelay}
 * polls the outbox. Gated by {@code orders.outbox.relay.enabled} (default {@code true})
 * so tests that do not need the relay can turn it off.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "orders.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayConfig {
}
