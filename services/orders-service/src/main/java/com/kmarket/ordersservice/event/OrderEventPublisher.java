package com.kmarket.ordersservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmarket.ordersservice.entity.OutboxEvent;
import com.kmarket.ordersservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Writes {@link OrderEvent}s into the {@code orders.outbox} table.
 *
 * <p>This only stages events; it never talks to Kafka. Because callers invoke it from
 * within their own {@code @Transactional} business method, the outbox row is committed
 * atomically with the order change. {@link com.kmarket.ordersservice.outbox.OutboxRelay}
 * is responsible for the actual Kafka publication.</p>
 */
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    /** Kafka topic for order lifecycle events (created by the {@code kafka-init} job). */
    public static final String ORDERS_TOPIC = "orders.events";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Stages an order event for publication, keyed by {@code order_id} so a given order's
     * events are produced to the same partition (and thus stay ordered).
     *
     * @param event the event to publish
     * @throws IllegalStateException if the event cannot be serialized to JSON
     */
    public void publish(OrderEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize order event " + event.eventId(), ex);
        }
        outboxEventRepository.save(
                new OutboxEvent(ORDERS_TOPIC, String.valueOf(event.orderId()), payload));
    }
}
