package com.kgiannopoulou.ledgerforge.shared.transaction;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Widens the window between "read balance" and "write balance" so race-condition tests can
 * reproduce lost updates reliably. A no-op unless {@code ledgerforge.concurrency.simulated-latency}
 * is set.
 */
@Component
public class SimulatedLatency {

    private final Duration latency;

    public SimulatedLatency(ConcurrencyProperties properties) {
        this.latency = properties.simulatedLatency();
    }

    public void pause() {
        if (latency.isZero() || latency.isNegative()) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
