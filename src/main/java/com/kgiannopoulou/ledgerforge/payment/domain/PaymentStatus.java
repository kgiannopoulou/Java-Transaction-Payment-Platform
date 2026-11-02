package com.kgiannopoulou.ledgerforge.payment.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Payment lifecycle.
 *
 * <pre>
 *   CREATED ──► AUTHORIZED ──► CAPTURED ──► PARTIALLY_REFUNDED ──► REFUNDED
 *      │            │              └──────────────────────────────────▲
 *      └────────────┴──► CANCELLED
 * </pre>
 *
 * The transition table is data, not scattered {@code if}s, so it can be tested exhaustively.
 */
public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    CANCELLED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = new EnumMap<>(PaymentStatus.class);

    static {
        TRANSITIONS.put(CREATED, EnumSet.of(AUTHORIZED, CANCELLED));
        TRANSITIONS.put(AUTHORIZED, EnumSet.of(CAPTURED, CANCELLED));
        TRANSITIONS.put(CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED));
        TRANSITIONS.put(PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED));
        TRANSITIONS.put(REFUNDED, EnumSet.noneOf(PaymentStatus.class));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(PaymentStatus.class));
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public Set<PaymentStatus> allowedTransitions() {
        return Collections.unmodifiableSet(TRANSITIONS.get(this));
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
