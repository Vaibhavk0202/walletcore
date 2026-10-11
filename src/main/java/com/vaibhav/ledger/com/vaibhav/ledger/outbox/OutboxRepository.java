package com.vaibhav.ledger.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OutboxRepository {

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID eventId, String aggregateType, UUID aggregateId, String eventType, String payload) {
        jdbc.sql("""
                INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (:eventId, :aggregateType, :aggregateId, :eventType, CAST(:payload AS JSONB))
                """)
                .param("eventId", eventId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payload)
                .update();
    }

    /**
     * Locks up to {@code limit} unpublished events, oldest first.
     * SKIP LOCKED makes a second relay instance skip rows the first one is already sending,
     * instead of waiting for them.
     */
    List<OutboxEvent> lockUnpublished(int limit) {
        return jdbc.sql("""
                SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload::text AS payload
                FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxEvent(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
    }

    void markPublished(List<Long> ids) {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }
}