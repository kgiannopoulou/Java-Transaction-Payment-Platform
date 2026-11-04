package com.kgiannopoulou.ledgerforge.shared.error;

import java.time.Duration;

/** A rate limit was exceeded (HTTP 429). {@code retryAfter} becomes the Retry-After header. */
public non-sealed class TooManyRequestsException extends DomainException {

    private final Duration retryAfter;

    public TooManyRequestsException(String type, String message, Duration retryAfter) {
        super(type, message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    @Override
    public String title() {
        return "Too many requests";
    }
}
