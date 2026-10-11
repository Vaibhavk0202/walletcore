package com.vaibhav.ledger.idempotency;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class IdempotencyRepository {

    record StoredKey(String requestHash, String responseBody) {
    }

    private final JdbcClient jdbc;

    IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tries to claim the key. Returns true if this transaction inserted it.
     * If another transaction inserted the same key and has not committed yet,
     * PostgreSQL makes this statement wait until that transaction finishes.
     */
    boolean tryClaim(String key, String requestHash) {
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_keys (idempotency_key, request_hash)
                VALUES (:key, :hash)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("key", key)
                .param("hash", requestHash)
                .update();
        return inserted == 1;
    }

    Optional<StoredKey> find(String key) {
        return jdbc.sql("SELECT request_hash, response_body FROM idempotency_keys WHERE idempotency_key = :key")
                .param("key", key)
                .query((rs, rowNum) -> new StoredKey(rs.getString("request_hash"), rs.getString("response_body")))
                .optional();
    }

    void saveResponse(String key, String responseBody) {
        jdbc.sql("UPDATE idempotency_keys SET response_body = :body WHERE idempotency_key = :key")
                .param("key", key)
                .param("body", responseBody)
                .update();
    }
}