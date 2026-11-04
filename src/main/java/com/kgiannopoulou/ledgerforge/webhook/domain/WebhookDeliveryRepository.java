package com.kgiannopoulou.ledgerforge.webhook.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository {

    /** {@code ON CONFLICT DO NOTHING} on (endpoint, event). */
    void enqueue(UUID endpointId, UUID eventId, String eventType, String payload, Instant now);

    /**
     * Atomically leases up to {@code limit} due deliveries with {@code FOR UPDATE SKIP LOCKED}, so
     * concurrent dispatchers on any number of instances never claim the same row. Claiming counts
     * the attempt and pushes {@code next_attempt_at} to {@code leaseUntil}: if the claimer dies,
     * the row simply becomes due again.
     */
    List<ClaimedDelivery> claimDue(Instant now, Instant leaseUntil, int limit);

    void recordAttempt(WebhookAttempt attempt);

    /**
     * The three transitions below apply only if {@code attemptNumber} is still the latest attempt,
     * so a worker that outlived its lease cannot overwrite a newer result.
     */
    void markDelivered(UUID id, int attemptNumber, Instant deliveredAt);

    void scheduleRetry(UUID id, int attemptNumber, Instant nextAttemptAt, @Nullable String error);

    void markDead(UUID id, int attemptNumber, @Nullable String error);

    /** DEAD back to PENDING. Returns {@code false} if the delivery was not DEAD. */
    boolean requeueDead(UUID id, Instant now);

    Optional<WebhookDelivery> findById(UUID id);

    /** Newest first; all statuses if {@code status} is null. */
    List<WebhookDelivery> findByEndpointId(UUID endpointId, @Nullable WebhookDeliveryStatus status, int limit);

    /** Oldest first. */
    List<WebhookAttempt> findAttempts(UUID deliveryId);

    /** The account owning the delivery's endpoint. Used by authorization checks. */
    Optional<UUID> findOwnerAccountId(UUID deliveryId);
}
