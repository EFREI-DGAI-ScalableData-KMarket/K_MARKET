package com.kmarket.ordersservice.outbox;

import com.kmarket.ordersservice.entity.OutboxEvent;
import com.kmarket.ordersservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes staged {@code orders.outbox} rows to Kafka (the publish half of the
 * transactional-outbox pattern, mirroring the stock-service relay).
 *
 * <p>On each tick it claims a batch of unpublished rows ({@code FOR UPDATE SKIP LOCKED}),
 * sends them to Kafka and only then stamps {@code published_at}. The send is confirmed
 * synchronously (broker ack), and the whole tick runs in one transaction: if Kafka is
 * unavailable the transaction rolls back and the batch is retried next tick. This yields
 * <em>at-least-once</em> delivery, so consumers must deduplicate on {@code event_id}.</p>
 *
 * <p>Disabled via {@code orders.outbox.relay.enabled=false} (e.g. in web-slice tests that
 * do not start Kafka).</p>
 */
@Component
@ConditionalOnProperty(name = "orders.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Max rows published per tick. */
    private static final int BATCH_SIZE = 200;

    /** How long to wait for each Kafka broker acknowledgement. */
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Publishes one batch of pending events. Scheduled with a fixed delay so ticks never
     * overlap. Returns the number of events published (also useful for tests).
     */
    @Scheduled(fixedDelayString = "${orders.outbox.relay.poll-ms:1000}")
    @Transactional
    public int publishPendingBatch() {
        List<OutboxEvent> batch = outboxEventRepository.findPendingBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return 0;
        }

        try {
            for (OutboxEvent event : batch) {
                // Block on the broker ack so we only mark the row published once it is durable.
                kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.setPublishedAt(OffsetDateTime.now());
            }
        } catch (Exception ex) {
            // Roll back: published_at stays null, the batch is retried on the next tick.
            Thread.currentThread().interrupt();
            throw new OutboxPublishException("Failed to publish order events to Kafka", ex);
        }

        outboxEventRepository.saveAll(batch);
        log.debug("Published {} order event(s) to Kafka", batch.size());
        return batch.size();
    }

    /** Thrown when a batch could not be published; triggers rollback and a retry. */
    static class OutboxPublishException extends RuntimeException {
        OutboxPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
