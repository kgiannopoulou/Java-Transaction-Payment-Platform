package com.kgiannopoulou.ledgerforge.shared.ephemeral;

import java.time.Duration;
import java.util.Optional;

/**
 * Fast, shared, <b>disposable</b> state: rate-limit windows, short-lived cache entries, temporary
 * risk signals. Implemented with Redis.
 *
 * <p>The contract is what matters: anything here may vanish at any moment, and losing all of it
 * must never lose or change money. Authoritative data stays in PostgreSQL. {@code ArchitectureTest}
 * keeps the ledger, account, transfer and payment modules from depending on this port.
 *
 * <p>Every method throws {@link EphemeralStoreUnavailableException} when the store cannot be
 * reached, because only the caller knows the right fallback.
 */
public interface EphemeralStore {

    /** Result of {@link #tryAcquire}. {@code retryAfter} is zero when allowed. */
    record Acquisition(boolean allowed, long count, Duration retryAfter) {
    }

    /**
     * Records a hit in a sliding window only if fewer than {@code limit} hits are in it. Atomic, so
     * concurrent callers on any number of instances cannot overshoot the limit.
     */
    Acquisition tryAcquire(String key, int limit, Duration window);

    /** Records a hit and returns how many hits the window now contains, this one included. */
    long increment(String key, Duration window);

    /** How many hits the window contains, without adding one. */
    long count(String key, Duration window);

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);
}
