package com.kgiannopoulou.ledgerforge.ratelimit.application;

import com.kgiannopoulou.ledgerforge.ratelimit.application.RateLimitProperties.Policy;
import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStore;
import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStoreUnavailableException;
import com.kgiannopoulou.ledgerforge.shared.error.TooManyRequestsException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Per-account limits shared by every instance.
 *
 * <p><b>Fails open.</b> If Redis is down, requests pass and a counter records it. A rate limit is
 * protective, not a correctness rule: everything that keeps money safe is in PostgreSQL and still
 * applies, so an outage of a helper must not become an outage of payments.
 */
@Service
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final EphemeralStore store;
    private final RateLimitProperties properties;
    private final MeterRegistry meters;

    public RateLimiter(EphemeralStore store, RateLimitProperties properties, MeterRegistry meters) {
        this.store = store;
        this.properties = properties;
        this.meters = meters;
    }

    /** @throws TooManyRequestsException when the account has used up its allowance */
    public void checkPaymentCreation(UUID accountId) {
        Policy policy = properties.payments();
        EphemeralStore.Acquisition acquisition;
        try {
            acquisition = store.tryAcquire("ratelimit:payments:" + accountId, policy.limit(), policy.window());
        } catch (EphemeralStoreUnavailableException e) {
            log.warn("Rate limiter unavailable, allowing the request: {}", e.getCause().getMessage());
            meters.counter("ledgerforge.ratelimit.decisions", "operation", "payments", "result", "unavailable").increment();
            return;
        }
        meters.counter("ledgerforge.ratelimit.decisions", "operation", "payments",
                "result", acquisition.allowed() ? "allowed" : "limited").increment();
        if (!acquisition.allowed()) {
            throw new TooManyRequestsException("RATE_LIMIT_EXCEEDED",
                    "At most " + policy.limit() + " payments per " + describe(policy.window()) + " per account",
                    acquisition.retryAfter());
        }
    }

    private static String describe(Duration window) {
        return window.toSeconds() % 60 == 0 ? window.toMinutes() + " minute(s)" : window.toSeconds() + " second(s)";
    }
}
