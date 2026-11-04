package com.kgiannopoulou.ledgerforge.webhook.domain;

import com.kgiannopoulou.ledgerforge.shared.error.InvalidRequestException;

/** The URL is malformed, not HTTPS, or points somewhere the platform must not call. */
public class InvalidWebhookUrlException extends InvalidRequestException {

    public InvalidWebhookUrlException(String reason) {
        super("INVALID_WEBHOOK_URL", reason);
    }
}
