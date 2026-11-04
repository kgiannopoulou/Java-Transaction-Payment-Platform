package com.kgiannopoulou.ledgerforge.webhook.application;

import org.jspecify.annotations.Nullable;

/** What came back: a status code, or an error if there was no HTTP response at all. */
public record WebhookResponse(@Nullable Integer status, @Nullable String error) {

    public static WebhookResponse status(int status) {
        return new WebhookResponse(status, status / 100 == 2 ? null : "HTTP " + status);
    }

    public static WebhookResponse failed(String error) {
        return new WebhookResponse(null, error);
    }

    public boolean isSuccess() {
        return status != null && status / 100 == 2;
    }
}
