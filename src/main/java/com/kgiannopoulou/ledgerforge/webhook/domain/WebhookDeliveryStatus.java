package com.kgiannopoulou.ledgerforge.webhook.domain;

/**
 * <pre>
 *   PENDING ──2xx──► DELIVERED
 *      │
 *      └──retries exhausted──► DEAD ──manual retry──► PENDING
 * </pre>
 */
public enum WebhookDeliveryStatus {
    PENDING,
    DELIVERED,
    DEAD
}
