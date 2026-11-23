package com.kmarket.ordersservice;

import com.kmarket.ordersservice.entity.OutboxEvent;
import com.kmarket.ordersservice.outbox.OutboxRelay;
import com.kmarket.ordersservice.repository.OutboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the transactional-outbox relay against a real Kafka
 * (Testcontainers): a staged {@code orders.outbox} row is published to the
 * {@code orders.events} topic and marked as published.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxRelayIntegrationTest {

    private static final String TOPIC = "orders.events";

    @Autowired
    private OutboxRelay outboxRelay;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private KafkaContainer kafkaContainer;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    void publishPendingBatch_sendsToKafkaAndMarksPublished() {
        OutboxEvent staged = outboxEventRepository.save(new OutboxEvent(
                TOPIC, "42", "{\"event_id\":\"e1\",\"event_type\":\"order_placed\",\"order_id\":42}"));

        int published = outboxRelay.publishPendingBatch();

        assertThat(published).isEqualTo(1);
        OutboxEvent reloaded = outboxEventRepository.findById(staged.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isNotNull();
        assertThat(outboxEventRepository.countByPublishedAtIsNull()).isZero();

        ConsumerRecord<String, String> record = consumeOne();
        assertThat(record).isNotNull();
        assertThat(record.key()).isEqualTo("42");
        assertThat(record.value()).contains("\"order_id\"").contains("42");
    }

    private ConsumerRecord<String, String> consumeOne() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "orders-relay-it");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            // Assign + seek-to-beginning (instead of subscribe) so we deterministically read
            // the record regardless of consumer-group rebalance timing.
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                if (!polled.isEmpty()) {
                    return polled.records(TOPIC).iterator().next();
                }
            }
            return null;
        }
    }
}
