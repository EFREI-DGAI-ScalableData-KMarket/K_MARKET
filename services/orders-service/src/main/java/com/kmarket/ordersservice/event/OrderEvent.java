package com.kmarket.ordersservice.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The JSON body of an order domain event published to the {@code orders.events} Kafka
 * topic. Follows the platform event convention (see {@code contracts/kafka-events-*}):
 * a unique {@code event_id} for consumer-side deduplication, an {@code event_type}
 * discriminator and an {@code occurred_at} timestamp, alongside the order snapshot.
 *
 * @param eventId        unique id; consumers dedupe on it (at-least-once delivery)
 * @param eventType      {@code order_placed} or {@code order_status_changed}
 * @param occurredAt     when the change happened
 * @param orderId        the order concerned
 * @param customerId     the order's customer
 * @param status         the order status after the change
 * @param previousStatus the status before the change; {@code null} for {@code order_placed}
 * @param totalCents     the order total in cents
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OrderEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("occurred_at") OffsetDateTime occurredAt,
        @JsonProperty("order_id") Integer orderId,
        @JsonProperty("customer_id") Integer customerId,
        String status,
        @JsonProperty("previous_status") String previousStatus,
        @JsonProperty("total_cents") Integer totalCents) {

    public static final String ORDER_PLACED = "order_placed";
    public static final String ORDER_STATUS_CHANGED = "order_status_changed";

    /** Event emitted once an order has been successfully created. */
    public static OrderEvent placed(Integer orderId, Integer customerId, String status, Integer totalCents) {
        return new OrderEvent(UUID.randomUUID().toString(), ORDER_PLACED,
                OffsetDateTime.now(), orderId, customerId, status, null, totalCents);
    }

    /** Event emitted when an order moves from {@code previousStatus} to {@code status}. */
    public static OrderEvent statusChanged(Integer orderId, Integer customerId,
                                           String previousStatus, String status, Integer totalCents) {
        return new OrderEvent(UUID.randomUUID().toString(), ORDER_STATUS_CHANGED,
                OffsetDateTime.now(), orderId, customerId, status, previousStatus, totalCents);
    }
}
