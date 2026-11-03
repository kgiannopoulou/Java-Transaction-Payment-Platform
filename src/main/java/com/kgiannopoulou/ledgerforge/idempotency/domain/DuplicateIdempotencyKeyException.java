package com.kgiannopoulou.ledgerforge.idempotency.domain;

import com.kgiannopoulou.ledgerforge.shared.error.ConflictException;

/**
 * An {@code Idempotency-Key} was reused with a <em>different</em> request body (HTTP 409).
 * Replaying the original response would hide a client bug; executing the new request would
 * break the key's at-most-once promise - so neither happens.
 */
public class DuplicateIdempotencyKeyException extends ConflictException {

    public DuplicateIdempotencyKeyException(String key) {
        super("IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key '" + key + "' was already used with a different request");
    }

    @Override
    public String title() {
        return "Idempotency key reused";
    }
}
