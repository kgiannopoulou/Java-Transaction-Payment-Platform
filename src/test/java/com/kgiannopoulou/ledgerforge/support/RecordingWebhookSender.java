package com.kgiannopoulou.ledgerforge.support;

import com.kgiannopoulou.ledgerforge.webhook.application.WebhookSender;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookNotification;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for the merchant's endpoint: counts every delivery attempt per event and can be
 * scripted to fail an event N times (or forever) to simulate an outage.
 */
public class RecordingWebhookSender implements WebhookSender {

    public static final int FOREVER = Integer.MAX_VALUE;

    private final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> successes = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> plannedFailures = new ConcurrentHashMap<>();

    public void failTimes(UUID eventId, int times) {
        plannedFailures.put(eventId, new AtomicInteger(times));
    }

    @Override
    public void send(WebhookNotification notification) {
        UUID eventId = notification.eventId();
        attempts.computeIfAbsent(eventId, id -> new AtomicInteger()).incrementAndGet();
        AtomicInteger remaining = plannedFailures.get(eventId);
        if (remaining != null && remaining.getAndUpdate(n -> n == FOREVER ? n : Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("simulated merchant endpoint outage for " + eventId);
        }
        successes.computeIfAbsent(eventId, id -> new AtomicInteger()).incrementAndGet();
    }

    public int attempts(UUID eventId) {
        AtomicInteger count = attempts.get(eventId);
        return count == null ? 0 : count.get();
    }

    public int successes(UUID eventId) {
        AtomicInteger count = successes.get(eventId);
        return count == null ? 0 : count.get();
    }
}
