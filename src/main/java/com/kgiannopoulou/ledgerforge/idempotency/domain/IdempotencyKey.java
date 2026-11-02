package com.kgiannopoulou.ledgerforge.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * Composite primary key. Keys are namespaced by {@code scope} (e.g. {@code "transfer"},
 * {@code "payment.capture"}) so the same client key used on two different endpoints never collides.
 */
@Embeddable
public record IdempotencyKey(
        @Column(name = "scope", nullable = false, length = 50) String scope,
        @Column(name = "idempotency_key", nullable = false, length = 255) String key) {

    public IdempotencyKey {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
    }
}
