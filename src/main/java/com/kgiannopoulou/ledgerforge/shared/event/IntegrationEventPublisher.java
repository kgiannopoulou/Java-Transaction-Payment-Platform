package com.kgiannopoulou.ledgerforge.shared.event;

/**
 * Port through which features publish integration events. The implementation (the outbox)
 * stores the event in the <em>current database transaction</em>; it is sent to Kafka only after
 * that transaction commits. Callers therefore never talk to Kafka directly, and an event exists
 * if and only if the state change it describes was committed.
 */
public interface IntegrationEventPublisher {

    /** Must be called inside the business transaction; fails if there is none. */
    void publish(IntegrationEvent event);
}
