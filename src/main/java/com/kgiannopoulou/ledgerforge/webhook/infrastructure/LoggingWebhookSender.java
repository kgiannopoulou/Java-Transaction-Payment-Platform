package com.kgiannopoulou.ledgerforge.webhook.infrastructure;

import com.kgiannopoulou.ledgerforge.webhook.application.WebhookSender;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in for an HTTP client that would POST a signed payload to the merchant's registered
 * URL. Merchant endpoint registration is out of scope; the consumer mechanics around it are not.
 */
@Component
class LoggingWebhookSender implements WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingWebhookSender.class);

    @Override
    public void send(WebhookNotification notification) {
        log.info("Webhook {} {} -> merchant account {}", notification.eventType(), notification.eventId(),
                notification.merchantAccountId());
    }
}
