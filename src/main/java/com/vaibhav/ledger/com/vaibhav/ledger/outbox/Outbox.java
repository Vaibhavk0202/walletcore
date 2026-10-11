package com.vaibhav.ledger.outbox;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** The only way to publish an event: write it to the outbox inside the caller's transaction. */
@Component
public class Outbox {

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;

    public Outbox(OutboxRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * MANDATORY means "there must already be a transaction". Calling this without one throws,
     * so an event can never be saved separately from the change it describes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        repository.insert(UUID.randomUUID(), aggregateType, aggregateId, eventType,
                jsonMapper.writeValueAsString(payload));
    }
}