package com.kgiannopoulou.ledgerforge.webhook.domain;

import java.util.UUID;

/**
 * What a merchant receives. {@code eventId} is included so the merchant can deduplicate too:
 * webhook delivery is at-least-once end to end.
 */
public record WebhookNotification(
        UUID eventId,
        String eventType,
        UUID merchantAccountId,
        UUID paymentId,
        String payload) {
}
