package com.kgiannopoulou.ledgerforge.outbox.infrastructure;

import com.kgiannopoulou.ledgerforge.outbox.application.OutboxProperties;
import com.kgiannopoulou.ledgerforge.outbox.application.OutboxTransport;
import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxMessage;
import com.kgiannopoulou.ledgerforge.shared.event.EventTopics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends synchronously and waits for the broker's acknowledgement ({@code acks=all}, idempotent
 * producer), so a message is only marked published once Kafka has it.
 */
@Component
class KafkaOutboxTransport implements OutboxTransport {

    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;

    KafkaOutboxTransport(KafkaTemplate<String, String> kafka, OutboxProperties properties) {
        this.kafka = kafka;
        this.properties = properties;
    }

    @Override
    public void send(OutboxMessage message) {
        var record = new ProducerRecord<String, String>(message.topic(), message.aggregateId().toString(),
                message.payload());
        record.headers()
                .add(EventTopics.HEADER_EVENT_ID, message.id().toString().getBytes(StandardCharsets.UTF_8))
                .add(EventTopics.HEADER_EVENT_TYPE, message.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + message.id(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not acknowledge " + message.id() + ": " + e, e);
        }
    }
}
