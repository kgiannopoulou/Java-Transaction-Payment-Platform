package com.kgiannopoulou.ledgerforge.messaging.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Consumer retry policy: exponential backoff ({@code initialInterval * multiplier^n}, capped at
 * {@code maxInterval}) for up to {@code maxRetries} retries, then the record goes to the DLT.
 */
@ConfigurationProperties("ledgerforge.kafka.retry")
public record KafkaRetryProperties(
        @DefaultValue("500ms") Duration initialInterval,
        @DefaultValue("2") int multiplier, // integer on purpose: no float fields anywhere (ArchitectureTest)
        @DefaultValue("10s") Duration maxInterval,
        @DefaultValue("4") int maxRetries,
        @DefaultValue("1") int replicationFactor,
        @DefaultValue("3") int partitions) {
}
