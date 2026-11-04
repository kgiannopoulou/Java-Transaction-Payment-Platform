package com.kgiannopoulou.ledgerforge.webhook.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param maxEndpointsPerAccount limits how far one event can fan out
 * @param connectTimeout         TCP + TLS connection deadline
 * @param requestTimeout         whole-request deadline, including reading the response
 * @param retrySchedule          wait after each failed attempt; once used up, the delivery is DEAD
 */
@ConfigurationProperties("ledgerforge.webhooks")
public record WebhookProperties(
        @DefaultValue("10") int maxEndpointsPerAccount,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue({"10s", "30s", "2m", "10m", "1h"}) List<Duration> retrySchedule,
        @DefaultValue Dispatcher dispatcher) {

    /**
     * @param batchSize        most deliveries claimed per poll
     * @param maxInFlight      concurrent HTTP calls. Bounded even on virtual threads: they make
     *                         threads cheap, not the sockets and database connections behind them
     * @param platformPoolSize worker count in {@link WorkerMode#PLATFORM} mode
     */
    public record Dispatcher(
            @DefaultValue("500") int batchSize,
            @DefaultValue("500") int maxInFlight,
            @DefaultValue("VIRTUAL") WorkerMode mode,
            @DefaultValue("50") int platformPoolSize) {
    }

    public enum WorkerMode {
        /** One virtual thread per delivery. */
        VIRTUAL,
        /** Classic fixed pool of platform threads. */
        PLATFORM
    }
}
