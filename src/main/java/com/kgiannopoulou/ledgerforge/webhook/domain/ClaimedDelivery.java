package com.kgiannopoulou.ledgerforge.webhook.domain;

import java.util.UUID;

/** A delivery a dispatcher has leased, with everything needed to send it. */
public record ClaimedDelivery(
        UUID id,
        UUID endpointId,
        UUID eventId,
        String payload,
        int attemptNumber,
        String url,
        String secretCiphertext,
        boolean endpointActive) {

    @Override
    public String toString() {
        return "ClaimedDelivery[id=" + id + ", attempt=" + attemptNumber + "]"; // keeps the payload out of logs
    }
}
