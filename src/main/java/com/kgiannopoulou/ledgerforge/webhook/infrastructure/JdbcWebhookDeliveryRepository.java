package com.kgiannopoulou.ledgerforge.webhook.infrastructure;

import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDeliveryRepository;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookNotification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
class JdbcWebhookDeliveryRepository implements WebhookDeliveryRepository {

    private final JdbcClient jdbc;

    JdbcWebhookDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordDelivered(WebhookNotification notification, Instant deliveredAt) {
        jdbc.sql("""
                        INSERT INTO webhook_deliveries (id, event_id, event_type, merchant_account_id, payment_id,
                                                        payload, delivered_at)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)""")
                .params(UUID.randomUUID(), notification.eventId(), notification.eventType(),
                        notification.merchantAccountId(), notification.paymentId(), notification.payload(),
                        deliveredAt.atOffset(ZoneOffset.UTC))
                .update();
    }
}
