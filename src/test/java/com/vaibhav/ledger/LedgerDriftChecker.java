package com.vaibhav.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The two reconciliation queries. Both must return no rows, or the ledger has drifted. */
public class LedgerDriftChecker {

    private final JdbcClient jdbc;

    public LedgerDriftChecker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Transactions whose entries do not sum to zero. */
    public List<UUID> unbalancedTransactions() {
        return jdbc.sql("""
                SELECT transaction_id
                FROM ledger_entries
                GROUP BY transaction_id
                HAVING SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) <> 0
                """)
                .query(UUID.class)
                .list();
    }

    /** Accounts whose cached balance differs from the sum of their entries. */
    public List<UUID> accountsWithBalanceMismatch() {
        return jdbc.sql("""
                SELECT a.id
                FROM accounts a
                LEFT JOIN ledger_entries e ON e.account_id = a.id
                GROUP BY a.id, a.balance
                HAVING a.balance <> COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
                """)
                .query(UUID.class)
                .list();
    }

    /** All balances together, system accounts included. Always zero in a closed double-entry ledger. */
    public long totalOfAllBalances() {
        return jdbc.sql("SELECT COALESCE(SUM(balance), 0) FROM accounts").query(Long.class).single();
    }

    public void assertNoDrift() {
        assertThat(unbalancedTransactions()).as("unbalanced transactions").isEmpty();
        assertThat(accountsWithBalanceMismatch()).as("accounts whose balance != sum of entries").isEmpty();
        assertThat(totalOfAllBalances()).as("sum of all balances").isZero();
    }
}