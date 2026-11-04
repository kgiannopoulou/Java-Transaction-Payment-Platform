package com.kgiannopoulou.ledgerforge.webhook.application;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Backoff as an explicit list rather than a formula: 10s, 30s, 2m, 10m, 1h roughly triples each
 * step, and the list is what operators and customers actually read in the documentation.
 */
public record RetrySchedule(List<Duration> delays) {

    public RetrySchedule {
        delays = List.copyOf(delays);
    }

    /** How long to wait after failed attempt {@code attemptNumber} (1-based); empty when used up. */
    public Optional<Duration> delayAfter(int attemptNumber) {
        return attemptNumber <= delays.size() ? Optional.of(delays.get(attemptNumber - 1)) : Optional.empty();
    }

    public int maxAttempts() {
        return delays.size() + 1;
    }
}
