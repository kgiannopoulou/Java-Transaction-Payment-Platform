package com.kgiannopoulou.ledgerforge.webhook.domain;

import java.time.Instant;

public interface WebhookDeliveryRepository {

    void recordDelivered(WebhookNotification notification, Instant deliveredAt);
}
