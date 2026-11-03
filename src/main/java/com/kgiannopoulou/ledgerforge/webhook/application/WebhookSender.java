package com.kgiannopoulou.ledgerforge.webhook.application;

import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookNotification;

/**
 * Delivers a notification to the merchant's endpoint. Throwing signals a failed delivery; the
 * Kafka error handler then retries with backoff and finally parks the event on the DLT.
 */
public interface WebhookSender {

    void send(WebhookNotification notification);
}
