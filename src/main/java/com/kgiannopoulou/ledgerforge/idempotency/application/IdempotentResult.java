package com.kgiannopoulou.ledgerforge.idempotency.application;

/**
 * @param body     the response body (freshly computed or deserialised from the stored response)
 * @param status   HTTP status to return
 * @param replayed true if this is a stored response for a previously completed request
 */
public record IdempotentResult<T>(T body, int status, boolean replayed) {

    static <T> IdempotentResult<T> fresh(T body, int status) {
        return new IdempotentResult<>(body, status, false);
    }

    static <T> IdempotentResult<T> replay(T body, int status) {
        return new IdempotentResult<>(body, status, true);
    }
}
