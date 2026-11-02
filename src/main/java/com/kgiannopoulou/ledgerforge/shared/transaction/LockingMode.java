package com.kgiannopoulou.ledgerforge.shared.transaction;

/**
 * How balance-critical rows are protected against concurrent modification.
 * See {@code docs/concurrency.md} for the experiment comparing the three modes.
 */
public enum LockingMode {

    /**
     * {@code SELECT ... FOR UPDATE} on every involved account, acquired in ascending id order so two
     * transfers in opposite directions cannot deadlock. The production default.
     */
    PESSIMISTIC,

    /**
     * Plain reads; the {@code @Version} column makes a concurrent writer fail at flush time and the
     * whole transaction is retried with jittered exponential backoff.
     */
    OPTIMISTIC,

    /**
     * Unsafe read-modify-write that bypasses the version check. Exists ONLY so the test suite can
     * reproduce lost updates and prove why the other two modes are needed.
     */
    NAIVE
}
