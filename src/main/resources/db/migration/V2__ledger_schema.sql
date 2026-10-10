CREATE TABLE accounts (
                          id             UUID        PRIMARY KEY,
                          owner_id       UUID,                               -- null for system accounts
                          type           VARCHAR(32) NOT NULL,               -- USER_WALLET, SYSTEM_SETTLEMENT
                          currency       VARCHAR(3)  NOT NULL,
                          balance        BIGINT      NOT NULL DEFAULT 0,     -- cache of SUM(entries), updated in the same transaction
                          allow_negative BOOLEAN     NOT NULL DEFAULT FALSE,
                          status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                          version        BIGINT      NOT NULL DEFAULT 0,     -- used by the optimistic-locking variant later
                          created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                          updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                          CONSTRAINT balance_non_negative CHECK (allow_negative OR balance >= 0)
);

CREATE TABLE ledger_transactions (
                                     id          UUID        PRIMARY KEY,
                                     type        VARCHAR(32) NOT NULL,                  -- DEPOSIT, TRANSFER
                                     status      VARCHAR(16) NOT NULL,                  -- POSTED
                                     reverses_id UUID REFERENCES ledger_transactions (id),
                                     description TEXT,
                                     created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
                                id             BIGSERIAL   PRIMARY KEY,
                                transaction_id UUID        NOT NULL REFERENCES ledger_transactions (id),
                                account_id     UUID        NOT NULL REFERENCES accounts (id),
                                direction      VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
                                amount         BIGINT      NOT NULL CHECK (amount > 0),
                                balance_after  BIGINT      NOT NULL,
                                created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_entries_account ON ledger_entries (account_id, id DESC);
CREATE INDEX idx_entries_transaction ON ledger_entries (transaction_id);

-- Money enters the system through this account, so every deposit still has a counter-entry.
INSERT INTO accounts (id, type, currency, allow_negative)
VALUES ('00000000-0000-0000-0000-000000000001', 'SYSTEM_SETTLEMENT', 'INR', TRUE);