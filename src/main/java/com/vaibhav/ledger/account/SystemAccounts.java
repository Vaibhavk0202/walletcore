package com.vaibhav.ledger.account;

import com.vaibhav.ledger.common.ApiException;
import java.util.UUID;

/** System accounts seeded by Flyway (see V2__ledger_schema.sql). */
public final class SystemAccounts {

    public static final UUID SETTLEMENT_INR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private SystemAccounts() {
    }

    public static UUID settlementFor(String currency) {
        if ("INR".equals(currency)) {
            return SETTLEMENT_INR;
        }
        throw ApiException.unprocessable("Unsupported currency: " + currency);
    }
}