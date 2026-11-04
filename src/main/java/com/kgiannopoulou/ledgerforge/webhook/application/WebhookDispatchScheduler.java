package com.kgiannopoulou.ledgerforge.webhook.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for due deliveries. Safe on any number of instances: claiming uses SKIP LOCKED. */
@Component
@ConditionalOnProperty(prefix = "ledgerforge.webhooks.dispatcher", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class WebhookDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchScheduler.class);

    private final WebhookDispatcher dispatcher;

    public WebhookDispatchScheduler(WebhookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${ledgerforge.webhooks.dispatcher.poll-interval-ms:1000}")
    public void dispatch() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException e) {
            // e.g. database unavailable; the next run tries again
            log.error("Webhook dispatch run failed", e);
        }
    }
}
