package com.kgiannopoulou.ledgerforge.outbox.application;

import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxMessage;
import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxStore;
import com.kgiannopoulou.ledgerforge.shared.event.EventTopics;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEvent;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEventCodec;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes events to the outbox table instead of Kafka. {@link Propagation#MANDATORY}: publishing
 * outside a business transaction is a bug (it would reintroduce the dual write), so it fails
 * loudly instead of silently opening a transaction of its own.
 */
@Service
public class OutboxEventPublisher implements IntegrationEventPublisher {

    private final OutboxStore store;
    private final IntegrationEventCodec codec;

    public OutboxEventPublisher(OutboxStore store, IntegrationEventCodec codec) {
        this.store = store;
        this.codec = codec;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(IntegrationEvent event) {
        store.append(new OutboxMessage(
                event.eventId(),
                EventTopics.aggregateTypeFor(event),
                event.aggregateId(),
                event.type(),
                EventTopics.topicFor(event),
                codec.encode(event),
                event.occurredAt(),
                0));
    }
}
