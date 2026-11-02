package com.kgiannopoulou.ledgerforge.shared.transaction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code ledgerforge.concurrency.*} settings.
 *
 * @param lockingMode      strategy used when loading accounts whose balance will change
 * @param maxAttempts      attempts (including the first) for transactions that hit a retryable
 *                         concurrency failure: optimistic-lock conflict, deadlock, serialization failure
 * @param initialBackoff   backoff ceiling before the second attempt; doubles each time, with full jitter
 * @param maxBackoff       upper bound for a single backoff
 * @param simulatedLatency artificial pause between reading and writing balances, used by race tests
 */
@ConfigurationProperties("ledgerforge.concurrency")
public record ConcurrencyProperties(
        @DefaultValue("PESSIMISTIC") LockingMode lockingMode,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("10ms") Duration initialBackoff,
        @DefaultValue("200ms") Duration maxBackoff,
        @DefaultValue("0ms") Duration simulatedLatency) {

    public ConcurrencyProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("ledgerforge.concurrency.max-attempts must be >= 1");
        }
    }
}
