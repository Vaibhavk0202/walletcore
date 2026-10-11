-- One row per Idempotency-Key a client has sent.
-- A row is inserted and its response saved in the same database transaction as the
-- money movement, so a key is either fully remembered with its result or not there at all.
CREATE TABLE idempotency_keys (
                                  idempotency_key TEXT        PRIMARY KEY,
                                  request_hash    TEXT        NOT NULL,
                                  response_body   TEXT,
                                  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Lets a cleanup job delete old keys quickly.
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);