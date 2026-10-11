package com.vaibhav.ledger.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves events from the outbox table to Kafka.
 * Delivery is at-least-once: if the app crashes after Kafka accepted an event but before the
 * row was marked published, the event is sent again. Consumers deduplicate with the event-id header.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;

    OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka, TransactionTemplate transaction) {
        this.repository = repository;
        this.kafka = kafka;
        this.transaction = transaction;
    }

    /** Runs again 500 ms after the previous run finishes, so two runs never overlap in one app. */
    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval-ms:500}")
    void publishPending() {
        try {
            int published;
            do {
                published = transaction.execute(status -> publishBatch());
            } while (published == BATCH_SIZE); // keep going while there is a backlog
        } catch (Exception e) {
            // Nothing is marked published, so the same events are retried on the next run.
            log.warn("Outbox relay failed, will retry: {}", e.getMessage());
        }
    }

    private int publishBatch() {
        List<OutboxEvent> batch = repository.lockUnpublished(BATCH_SIZE);
        if (batch.isEmpty()) {
            return 0;
        }
        // Send the whole batch, then wait for Kafka to acknowledge every message.
        CompletableFuture<?>[] sends = batch.stream().map(this::send).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(sends).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka did not acknowledge the batch", e);
        }
        repository.markPublished(batch.stream().map(OutboxEvent::id).toList());
        return batch.size();
    }

    private CompletableFuture<?> send(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                OutboxConfig.LEDGER_TRANSACTIONS_TOPIC, event.aggregateId().toString(), event.payload());
        record.headers()
                .add("event-id", event.eventId().toString().getBytes(StandardCharsets.UTF_8))
                .add("event-type", event.eventType().getBytes(StandardCharsets.UTF_8));
        return kafka.send(record);
    }
}