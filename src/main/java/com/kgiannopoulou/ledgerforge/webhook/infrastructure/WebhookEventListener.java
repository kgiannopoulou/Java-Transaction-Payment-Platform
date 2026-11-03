package com.kgiannopoulou.ledgerforge.webhook.infrastructure;

import com.kgiannopoulou.ledgerforge.messaging.infrastructure.ConsumedEvents;
import com.kgiannopoulou.ledgerforge.shared.event.EventTopics;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookNotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class WebhookEventListener {

    private final ConsumedEvents events;
    private final WebhookNotificationService webhooks;

    WebhookEventListener(ConsumedEvents events, WebhookNotificationService webhooks) {
        this.events = events;
        this.webhooks = webhooks;
    }

    @KafkaListener(id = "merchant-webhooks", groupId = "ledgerforge-webhooks", topics = EventTopics.PAYMENTS,
            autoStartup = "${ledgerforge.kafka.consumers.enabled:true}")
    void on(ConsumerRecord<String, String> record) {
        webhooks.handle(events.decode(record));
    }
}
