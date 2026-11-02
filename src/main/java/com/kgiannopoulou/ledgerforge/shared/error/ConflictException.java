package com.kgiannopoulou.ledgerforge.shared.error;

/**
 * The request conflicts with the current state of a resource: an invalid state transition, a
 * duplicate, a reused idempotency key, or a concurrent modification that survived all retries
 * (HTTP 409).
 */
public non-sealed class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
