package com.vaibhav.ledger.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID ownerId, AccountType type, String currency) {
        jdbc.sql("""
                INSERT INTO accounts (id, owner_id, type, currency)
                VALUES (:id, :ownerId, :type, :currency)
                """)
                .param("id", id)
                .param("ownerId", ownerId)
                .param("type", type.name())
                .param("currency", currency)
                .update();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT * FROM accounts WHERE id = :id")
                .param("id", id)
                .query(AccountRepository::mapRow)
                .optional();
    }

    /**
     * Reads the account and locks its row until the current transaction commits or rolls back.
     * Any other transaction that asks for the same lock waits here.
     */
    public Optional<Account> findByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT * FROM accounts WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(AccountRepository::mapRow)
                .optional();
    }

    /** Adds delta to the cached balance and returns the new balance. */
    public long applyDelta(UUID id, long delta) {
        return jdbc.sql("""
                UPDATE accounts
                SET balance = balance + :delta, updated_at = now()
                WHERE id = :id
                RETURNING balance
                """)
                .param("id", id)
                .param("delta", delta)
                .query(Long.class)
                .single();
    }

    private static Account mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                AccountType.valueOf(rs.getString("type")),
                rs.getString("currency"),
                rs.getLong("balance"),
                rs.getBoolean("allow_negative"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}