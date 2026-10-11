package com.vaibhav.ledger.outbox;

import java.util.UUID;

record OutboxEvent(long id, UUID eventId, String aggregateType, UUID aggregateId, String eventType, String payload) {
}