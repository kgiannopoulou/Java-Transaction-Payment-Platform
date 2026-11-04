package com.kgiannopoulou.ledgerforge.payment.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics, exported as {@code payments_created_total} and {@code payments_failed_total}.
 *
 * <p>Called from the controller, after the transaction has resolved. Counting inside it would also
 * count work that later rolls back, and idempotent replays must not look like new payments.
 *
 * <p>Tags are bounded sets (operation and error type). Ids never go in a tag: every value creates a
 * new time series, and one per payment would take Prometheus down.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry meters;
    private final Counter created;

    public PaymentMetrics(MeterRegistry meters) {
        this.meters = meters;
        this.created = meters.counter("payments.created"); // exists from the start, so rates begin at zero
    }

    public void created() {
        created.increment();
    }

    /** @param reason the stable error type, e.g. {@code PAYMENT_BLOCKED} */
    public void failed(String operation, String reason) {
        meters.counter("payments.failed", "operation", operation, "reason", reason).increment();
    }
}
