package com.kgiannopoulou.ledgerforge.messaging.infrastructure;

import com.kgiannopoulou.ledgerforge.shared.event.EventTopics;
import com.kgiannopoulou.ledgerforge.shared.event.MalformedEventException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.ArrayList;
import java.util.List;

/**
 * Topics and the consumer failure policy.
 *
 * <pre>
 *   record ──► listener ──fails──► retry with exponential backoff (same consumer, seek back)
 *                                      │ still failing after maxRetries,
 *                                      │ or MalformedEventException (retrying cannot help)
 *                                      ▼
 *                                <topic>.DLT   (original record + exception headers)
 * </pre>
 *
 * Retries are in-place (the partition waits), which keeps per-aggregate ordering. Everything a
 * listener does is idempotent ({@code ProcessedEventLog}), so a retry or a redelivery after a
 * crash never repeats a side effect.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfiguration.class);

    private static final List<String> TOPICS = List.of(EventTopics.PAYMENTS, EventTopics.TRANSFERS, EventTopics.ACCOUNTS);

    @Bean
    KafkaAdmin.NewTopics ledgerforgeTopics(KafkaRetryProperties properties) {
        List<org.apache.kafka.clients.admin.NewTopic> topics = new ArrayList<>();
        for (String topic : TOPICS) {
            topics.add(TopicBuilder.name(topic)
                    .partitions(properties.partitions()).replicas(properties.replicationFactor()).build());
            topics.add(TopicBuilder.name(EventTopics.deadLetterTopic(topic))
                    .partitions(1).replicas(properties.replicationFactor()).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(org.apache.kafka.clients.admin.NewTopic[]::new));
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, KafkaRetryProperties properties) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(EventTopics.deadLetterTopic(record.topic()), 0));

        var backOff = new ExponentialBackOff(properties.initialInterval().toMillis(), properties.multiplier());
        backOff.setMaxInterval(properties.maxInterval().toMillis());
        backOff.setMaxAttempts(properties.maxRetries());

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        handler.setRetryListeners((record, exception, attempt) ->
                log.warn("Consumer failed on {}-{}@{} (attempt {}): {}", record.topic(), record.partition(),
                        record.offset(), attempt, rootMessage(exception)));
        return handler;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
