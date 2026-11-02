package com.kgiannopoulou.ledgerforge.idempotency.api;

import com.kgiannopoulou.ledgerforge.idempotency.application.IdempotentResult;
import org.springframework.http.ResponseEntity;

/** HTTP conventions for idempotent endpoints (same header names as Stripe and the IETF draft). */
public final class IdempotencyHeaders {

    public static final String KEY = "Idempotency-Key";
    public static final String REPLAYED = "Idempotent-Replayed";

    private IdempotencyHeaders() {
    }

    public static <T> ResponseEntity<T> respond(IdempotentResult<T> result) {
        var builder = ResponseEntity.status(result.status());
        if (result.replayed()) {
            builder.header(REPLAYED, "true");
        }
        return builder.body(result.body());
    }
}
