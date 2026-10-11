-- Events waiting to be published to Kafka.
-- A row is written in the same database transaction as the ledger change it describes,
-- so an event exists if and only if the change committed.
CREATE TABLE outbox_events (
                               id             BIGSERIAL   PRIMARY KEY,
                               event_id       UUID        NOT NULL UNIQUE,
                               aggregate_type TEXT        NOT NULL,
                               aggregate_id   UUID        NOT NULL,
                               event_type     TEXT        NOT NULL,
                               payload        JSONB       NOT NULL,
                               created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                               published_at   TIMESTAMPTZ
);

-- The relay only ever looks for unpublished rows, oldest first.
-- A partial index stays small because published rows drop out of it.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (id) WHERE published_at IS NULL;