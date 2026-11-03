package com.kgiannopoulou.ledgerforge.outbox.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param batchSize      messages locked and sent per relay transaction
 * @param initialBackoff delay before retrying a message whose send failed; doubles per attempt
 * @param maxBackoff     cap for that delay
 * @param retention      published rows are deleted after this long
 * @param sendTimeout    how long to wait for the broker's acknowledgement
 */
@ConfigurationProperties("ledgerforge.outbox.relay")
public record OutboxProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("10s") Duration sendTimeout) {
}
