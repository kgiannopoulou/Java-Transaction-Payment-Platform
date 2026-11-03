package com.kgiannopoulou.ledgerforge.outbox.application;

import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxMessage;

/** Sends one outbox message to the broker and returns only once the broker acknowledged it. */
public interface OutboxTransport {

    /** @throws RuntimeException if the broker did not acknowledge the message */
    void send(OutboxMessage message);
}
