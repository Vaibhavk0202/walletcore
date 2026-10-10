package com.vaibhav.ledger.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertTransaction(UUID id, TransactionType type, String description) {
        jdbc.sql("""
                INSERT INTO ledger_transactions (id, type, status, description)
                VALUES (:id, :type, 'POSTED', :description)
                """)
                .param("id", id)
                .param("type", type.name())
                .param("description", description)
                .update();
    }

    public LedgerEntry insertEntry(UUID transactionId, Posting posting, long balanceAfter) {
        return jdbc.sql("""
                INSERT INTO ledger_entries (transaction_id, account_id, direction, amount, balance_after)
                VALUES (:transactionId, :accountId, :direction, :amount, :balanceAfter)
                RETURNING *
                """)
                .param("transactionId", transactionId)
                .param("accountId", posting.accountId())
                .param("direction", posting.direction().name())
                .param("amount", posting.amount())
                .param("balanceAfter", balanceAfter)
                .query(LedgerRepository::mapEntry)
                .single();
    }

    /** Keyset pagination: newest first, entries with id below beforeId (or all when null). */
    public List<LedgerEntry> findEntries(UUID accountId, Long beforeId, int limit) {
        return jdbc.sql("""
                SELECT * FROM ledger_entries
                WHERE account_id = :accountId
                  AND (CAST(:beforeId AS BIGINT) IS NULL OR id < :beforeId)
                ORDER BY id DESC
                LIMIT :limit
                """)
                .param("accountId", accountId)
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query(LedgerRepository::mapEntry)
                .list();
    }

    private static LedgerEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerEntry(
                rs.getLong("id"),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                Direction.valueOf(rs.getString("direction")),
                rs.getLong("amount"),
                rs.getLong("balance_after"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}