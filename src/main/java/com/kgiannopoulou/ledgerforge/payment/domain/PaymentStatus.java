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
 *    │ ▲ │          │              └──────────────────────────────────▲
 *    │ │ └──────────┴──► CANCELLED
 *    ▼ │ approved            ▲
 *   UNDER_REVIEW ────────────┘ rejected
 * </pre>
 *
 * A payment the risk engine wants a person to look at waits in {@code UNDER_REVIEW}, where no
 * money can move: it cannot be authorized until a reviewer approves it.
 *
 * The transition table is data, not scattered {@code if}s, so it can be tested exhaustively.
 */
public enum PaymentStatus {
    CREATED,
    UNDER_REVIEW,
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    CANCELLED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = new EnumMap<>(PaymentStatus.class);

    static {
        TRANSITIONS.put(CREATED, EnumSet.of(UNDER_REVIEW, AUTHORIZED, CANCELLED));
        TRANSITIONS.put(UNDER_REVIEW, EnumSet.of(CREATED, CANCELLED));
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

    /** True once the payer's funds have been moved to the merchant. */
    public boolean hasBeenCaptured() {
        return this == CAPTURED || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
