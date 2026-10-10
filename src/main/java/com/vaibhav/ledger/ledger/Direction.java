package com.vaibhav.ledger.ledger;

/** Wallets are what the platform owes users, so a CREDIT raises their balance. */
public enum Direction {
    DEBIT(-1),
    CREDIT(1);

    private final int sign;

    Direction(int sign) {
        this.sign = sign;
    }

    public long signed(long amount) {
        return sign * amount;
    }
}