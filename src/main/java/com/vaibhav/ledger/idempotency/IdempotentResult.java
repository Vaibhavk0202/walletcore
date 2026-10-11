package com.vaibhav.ledger.idempotency;

/** The response to send, and whether it was replayed from an earlier request with the same key. */
public record IdempotentResult<T>(T body, boolean replayed) {
        }