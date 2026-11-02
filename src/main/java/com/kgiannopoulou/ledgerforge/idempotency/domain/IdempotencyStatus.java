package com.kgiannopoulou.ledgerforge.idempotency.domain;

public enum IdempotencyStatus {
    /** Inserted and locked by the transaction currently executing the request. Never committed as-is. */
    IN_PROGRESS,
    /** Business operation committed together with its stored response. */
    COMPLETED
}
