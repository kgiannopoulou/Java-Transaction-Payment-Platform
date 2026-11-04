package com.kgiannopoulou.ledgerforge.risk.application;

import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStore;
import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStoreUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Short-term behaviour per account, kept as distributed counters in Redis so that every instance
 * sees the same numbers. These are only useful for minutes and expire by themselves. They are not
 * records: what was decided, and why, is stored permanently in {@code risk_decisions}.
 *
 * <p>Failures are recorded after the transaction has rolled back, so they survive it. That is an
 * advantage of keeping them out of the database transaction.
 */
@Component
public class RiskSignals {

    /** {@code available} is false if Redis could not be reached; the counts are then meaningless. */
    public record Snapshot(long attempts, long failures, boolean available) {
    }

    private static final Logger log = LoggerFactory.getLogger(RiskSignals.class);

    private final EphemeralStore store;
    private final RiskProperties.Rules rules;
    private final MeterRegistry meters;

    public RiskSignals(EphemeralStore store, RiskProperties properties, MeterRegistry meters) {
        this.store = store;
        this.rules = properties.rules();
        this.meters = meters;
    }

    /** Counts this attempt and reads the current signals. */
    public Snapshot recordAttempt(UUID accountId) {
        try {
            long attempts = store.increment(attemptsKey(accountId), rules.velocity().window());
            long failures = store.count(failuresKey(accountId), rules.repeatedFailure().window());
            return new Snapshot(attempts, failures, true);
        } catch (EphemeralStoreUnavailableException e) {
            unavailable(e);
            return new Snapshot(0, 0, false);
        }
    }

    public void recordFailure(UUID accountId) {
        try {
            store.increment(failuresKey(accountId), rules.repeatedFailure().window());
        } catch (EphemeralStoreUnavailableException e) {
            unavailable(e);
        }
    }

    private void unavailable(EphemeralStoreUnavailableException e) {
        log.warn("Risk signals unavailable: {}", e.getCause().getMessage());
        meters.counter("ledgerforge.risk.signals.unavailable").increment();
    }

    private static String attemptsKey(UUID accountId) {
        return "risk:attempts:" + accountId;
    }

    private static String failuresKey(UUID accountId) {
        return "risk:failures:" + accountId;
    }
}
