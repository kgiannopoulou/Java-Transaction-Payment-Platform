package com.kgiannopoulou.ledgerforge.webhook.application;

/** Makes one HTTP attempt. Never throws for delivery failures: they are ordinary results. */
public interface WebhookSender {

    WebhookResponse send(WebhookRequest request);
}
