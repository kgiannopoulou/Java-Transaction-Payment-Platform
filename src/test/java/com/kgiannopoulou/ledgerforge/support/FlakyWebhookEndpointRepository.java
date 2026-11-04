package com.kgiannopoulou.ledgerforge.support;

import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpoint;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpointRepository;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEventType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps the real repository so the Kafka tests can make the webhook consumer fail: lookups for a
 * scripted account throw N times (or forever), which is what a failing database looks like to the
 * consumer. Merchant outages no longer reach the consumer at all; those are covered by
 * {@code WebhookDeliveryTest}.
 */
public class FlakyWebhookEndpointRepository implements WebhookEndpointRepository {

    public static final int FOREVER = Integer.MAX_VALUE;

    private final WebhookEndpointRepository real;
    private final Map<UUID, AtomicInteger> lookups = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> plannedFailures = new ConcurrentHashMap<>();

    public FlakyWebhookEndpointRepository(WebhookEndpointRepository real) {
        this.real = real;
    }

    public void failTimes(UUID accountId, int times) {
        plannedFailures.put(accountId, new AtomicInteger(times));
    }

    /** How often subscribers were looked up for this account, i.e. how often the consumer really ran. */
    public int lookups(UUID accountId) {
        AtomicInteger count = lookups.get(accountId);
        return count == null ? 0 : count.get();
    }

    @Override
    public List<WebhookEndpoint> findActiveSubscribers(Collection<UUID> accountIds, WebhookEventType type) {
        for (UUID accountId : accountIds) {
            lookups.computeIfAbsent(accountId, id -> new AtomicInteger()).incrementAndGet();
            AtomicInteger remaining = plannedFailures.get(accountId);
            if (remaining != null && remaining.getAndUpdate(n -> n == FOREVER ? n : Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("simulated failure looking up endpoints of " + accountId);
            }
        }
        return real.findActiveSubscribers(accountIds, type);
    }

    @Override
    public void save(WebhookEndpoint endpoint, String secretCiphertext) {
        real.save(endpoint, secretCiphertext);
    }

    @Override
    public Optional<WebhookEndpoint> findById(UUID id) {
        return real.findById(id);
    }

    @Override
    public List<WebhookEndpoint> findByAccountId(UUID accountId) {
        return real.findByAccountId(accountId);
    }

    @Override
    public long countActive(UUID accountId) {
        return real.countActive(accountId);
    }

    @Override
    public boolean disable(UUID id, Instant now) {
        return real.disable(id, now);
    }
}
