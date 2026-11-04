package com.kgiannopoulou.ledgerforge.webhook.application;

import java.util.UUID;

/** A fully prepared HTTP call. {@code eventId} is the public {@code evt_...} id. */
public record WebhookRequest(UUID deliveryId, String eventId, String url, String body, String signature, int attempt) {
}
