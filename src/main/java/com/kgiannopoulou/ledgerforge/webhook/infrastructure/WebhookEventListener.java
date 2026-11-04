package com.kgiannopoulou.ledgerforge.webhook.infrastructure;

import com.kgiannopoulou.ledgerforge.messaging.infrastructure.ConsumedEvents;
import com.kgiannopoulou.ledgerforge.shared.event.EventTopics;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookFanOutService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class WebhookEventListener {

    private final ConsumedEvents events;
    private final WebhookFanOutService fanOut;

    WebhookEventListener(ConsumedEvents events, WebhookFanOutService fanOut) {
        this.events = events;
        this.fanOut = fanOut;
    }

    @KafkaListener(id = "webhooks", groupId = "ledgerforge-webhooks",
            topics = {EventTopics.PAYMENTS, EventTopics.TRANSFERS, EventTopics.ACCOUNTS},
            autoStartup = "${ledgerforge.kafka.consumers.enabled:true}")
    void on(ConsumerRecord<String, String> record) {
        fanOut.handle(events.decode(record));
    }
}
