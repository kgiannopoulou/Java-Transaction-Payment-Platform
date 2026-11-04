package com.kgiannopoulou.ledgerforge.transfer.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code transfers_total} and {@code transfer_duration_seconds}, tagged by outcome. The histogram
 * buckets are what let Prometheus compute a p95 across instances; averaging percentiles that each
 * instance computed for itself would give a meaningless number.
 */
@Component
public class TransferMetrics {

    public enum Outcome {
        COMPLETED, FAILED, REPLAYED;

        String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final MeterRegistry meters;

    public TransferMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    public void record(Outcome outcome, Duration duration) {
        meters.counter("transfers", "outcome", outcome.tag()).increment();
        Timer.builder("transfer.duration")
                .tag("outcome", outcome.tag())
                .publishPercentileHistogram()
                .register(meters)
                .record(duration);
    }
}
