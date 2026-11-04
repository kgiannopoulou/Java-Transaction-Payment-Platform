package com.kgiannopoulou.ledgerforge.webhook.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A URL registered for an account. The signing secret is deliberately not part of this type. */
public record WebhookEndpoint(
        UUID id,
        UUID accountId,
        String url,
        @Nullable String description,
        Set<WebhookEventType> eventTypes,
        WebhookEndpointStatus status,
        Instant createdAt) {

    public boolean isActive() {
        return status == WebhookEndpointStatus.ACTIVE;
    }
}
