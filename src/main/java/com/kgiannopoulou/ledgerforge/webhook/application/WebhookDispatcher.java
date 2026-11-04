package com.kgiannopoulou.ledgerforge.webhook.application;

import com.kgiannopoulou.ledgerforge.webhook.domain.ClaimedDelivery;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookAttempt;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookAttemptOutcome;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDeliveryRepository;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookSignature;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Sends due deliveries:
 *
 * <pre>
 *   claim (one statement, SKIP LOCKED, leases the rows)
 *     └─► per delivery, on a worker:  sign ─► HTTP ─► one transaction: attempt row + next state
 * </pre>
 *
 * No database connection or transaction is held during HTTP, so a slow merchant costs a cheap
 * worker and nothing else. Claiming only as many rows as there are free slots means a claimed
 * row is always being worked on, which is what makes the lease safe: the lease only has to
 * outlast one request, never time spent waiting in a queue.
 */
@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    /** Time for a worker to record its result after the request deadline has passed. */
    private static final Duration LEASE_MARGIN = Duration.ofSeconds(30);

    private static final int MAX_ERROR_LENGTH = 500;

    private final WebhookDeliveryRepository deliveries;
    private final WebhookSender sender;
    private final WebhookSecrets secrets;
    private final WebhookUrlPolicy urlPolicy;
    private final WebhookWorkers workers;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MeterRegistry meters;
    private final RetrySchedule retries;
    private final Duration lease;
    private final int batchSize;
    private final Semaphore slots;

    public WebhookDispatcher(WebhookDeliveryRepository deliveries, WebhookSender sender, WebhookSecrets secrets,
                             WebhookUrlPolicy urlPolicy, WebhookWorkers workers, TransactionTemplate transactions,
                             WebhookProperties properties, Clock clock, MeterRegistry meters) {
        this.deliveries = deliveries;
        this.sender = sender;
        this.secrets = secrets;
        this.urlPolicy = urlPolicy;
        this.workers = workers;
        this.transactions = transactions;
        this.clock = clock;
        this.meters = meters;
        this.retries = new RetrySchedule(properties.retrySchedule());
        this.lease = properties.requestTimeout().plus(LEASE_MARGIN);
        this.batchSize = properties.dispatcher().batchSize();
        this.slots = new Semaphore(workers.capacity());
    }

    /** Claims and starts due deliveries without waiting for them. Returns how many were started. */
    public int dispatchDue() {
        int capacity = Math.min(slots.availablePermits(), batchSize);
        if (capacity == 0) {
            return 0;
        }
        Instant now = clock.instant();
        List<ClaimedDelivery> claimed = deliveries.claimDue(now, now.plus(lease), capacity);
        for (ClaimedDelivery delivery : claimed) {
            slots.acquireUninterruptibly();
            workers.execute(() -> {
                try {
                    attempt(delivery);
                } catch (RuntimeException e) {
                    // e.g. the database went away. The lease expires and the delivery is retried.
                    log.error("Webhook attempt for delivery {} could not be recorded", delivery.id(), e);
                } finally {
                    slots.release();
                }
            });
        }
        return claimed.size();
    }

    private void attempt(ClaimedDelivery delivery) {
        Instant startedAt = clock.instant();
        long start = System.nanoTime();
        WebhookResponse response = delivery.endpointActive()
                ? send(delivery, startedAt)
                : WebhookResponse.failed("Endpoint is disabled");
        long durationMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        Instant finishedAt = clock.instant();
        String error = truncate(response.error());
        Optional<Duration> retryIn = delivery.endpointActive()
                ? retries.delayAfter(delivery.attemptNumber())
                : Optional.empty();

        transactions.executeWithoutResult(status -> {
            deliveries.recordAttempt(new WebhookAttempt(UUID.randomUUID(), delivery.id(), delivery.attemptNumber(),
                    startedAt, durationMs,
                    response.isSuccess() ? WebhookAttemptOutcome.SUCCESS : WebhookAttemptOutcome.FAILURE,
                    response.status(), error));
            if (response.isSuccess()) {
                deliveries.markDelivered(delivery.id(), delivery.attemptNumber(), finishedAt);
            } else if (retryIn.isPresent()) {
                deliveries.scheduleRetry(delivery.id(), delivery.attemptNumber(), finishedAt.plus(retryIn.get()), error);
            } else {
                deliveries.markDead(delivery.id(), delivery.attemptNumber(), error);
            }
        });

        meters.counter("ledgerforge.webhooks.attempts",
                "outcome", response.isSuccess() ? "success" : "failure").increment();
        if (!response.isSuccess() && retryIn.isEmpty()) {
            meters.counter("ledgerforge.webhooks.dead").increment();
            log.warn("Webhook delivery {} is dead after attempt {}: {}", delivery.id(), delivery.attemptNumber(), error);
        }
    }

    private WebhookResponse send(ClaimedDelivery delivery, Instant now) {
        try {
            // Checked again here, not only at registration: DNS can change after the URL was accepted.
            urlPolicy.validate(delivery.url());
        } catch (RuntimeException e) {
            return WebhookResponse.failed(e.getMessage());
        }
        String signature = WebhookSignature.sign(secrets.decrypt(delivery.secretCiphertext()), now.getEpochSecond(),
                delivery.payload());
        return sender.send(new WebhookRequest(delivery.id(), WebhookEvent.publicId(delivery.eventId()), delivery.url(),
                delivery.payload(), signature, delivery.attemptNumber()));
    }

    private static String truncate(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
