package com.vaibhav.ledger.ledger;

import java.util.List;
import java.util.UUID;

public record LedgerTransaction(
        UUID id,
        TransactionType type,
        String status,
        String description,
        List<LedgerEntry> entries) {
}