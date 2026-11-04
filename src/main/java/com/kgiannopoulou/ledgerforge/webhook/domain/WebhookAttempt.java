package com.kgiannopoulou.ledgerforge.webhook.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** One HTTP attempt: the delivery history. Rows are immutable (enforced by a trigger). */
public record WebhookAttempt(
        UUID id,
        UUID deliveryId,
        int attemptNumber,
        Instant startedAt,
        long durationMs,
        WebhookAttemptOutcome outcome,
        @Nullable Integer responseStatus,
        @Nullable String error) {
}
