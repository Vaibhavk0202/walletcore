package com.vaibhav.ledger.ledger;

import java.util.UUID;

/** One line of a transaction before it is written: move amount on accountId in direction. */
public record Posting(UUID accountId, Direction direction, long amount) {

    public static Posting debit(UUID accountId, long amount) {
        return new Posting(accountId, Direction.DEBIT, amount);
    }

    public static Posting credit(UUID accountId, long amount) {
        return new Posting(accountId, Direction.CREDIT, amount);
    }
}