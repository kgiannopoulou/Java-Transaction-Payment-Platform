package com.kgiannopoulou.ledgerforge.webhook.domain;

import java.util.Arrays;
import java.util.Optional;

/** Event types customers can subscribe to. The wire names are a public contract. */
public enum WebhookEventType {
    PAYMENT_CAPTURED("payment.captured"),
    PAYMENT_REFUNDED("payment.refunded"),
    TRANSFER_COMPLETED("transfer.completed"),
    ACCOUNT_FROZEN("account.frozen");

    private final String wireName;

    WebhookEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<WebhookEventType> fromWireName(String wireName) {
        return Arrays.stream(values()).filter(type -> type.wireName.equals(wireName)).findFirst();
    }
}
