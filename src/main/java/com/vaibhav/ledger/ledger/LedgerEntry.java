package com.vaibhav.ledger.ledger;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
        long id,
        UUID transactionId,
        UUID accountId,
        Direction direction,
        long amount,
        long balanceAfter,
        Instant createdAt) {
}