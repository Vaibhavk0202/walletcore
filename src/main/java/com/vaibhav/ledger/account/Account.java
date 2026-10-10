package com.vaibhav.ledger.account;

import java.time.Instant;
import java.util.UUID;

public record Account(
        UUID id,
        UUID ownerId,
        AccountType type,
        String currency,
        long balance,
        boolean allowNegative,
        String status,
        Instant createdAt) {
}