package com.kgiannopoulou.ledgerforge.webhook.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** One event on its way to one endpoint. */
public record WebhookDelivery(
        UUID id,
        UUID endpointId,
        UUID eventId,
        String eventType,
        String payload,
        WebhookDeliveryStatus status,
        int attempts,
        Instant nextAttemptAt,
        @Nullable String lastError,
        Instant createdAt,
        @Nullable Instant deliveredAt) {
}
