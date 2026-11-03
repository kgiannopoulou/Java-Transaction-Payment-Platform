package com.kgiannopoulou.ledgerforge.outbox.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence for the transactional outbox. */
public interface OutboxStore {

    /** Inserts a pending message, in the caller's transaction. */
    void append(OutboxMessage message);

    /**
     * Locks and returns the oldest due, unpublished messages ({@code FOR UPDATE SKIP LOCKED}), so
     * several relay instances can run without publishing the same row twice concurrently.
     * A message is never returned while an earlier message of the same aggregate is still
     * unpublished, so events of one aggregate reach Kafka in commit order even across retries;
     * other aggregates are not held up.
     */
    List<OutboxMessage> lockNextBatch(Instant now, int limit);

    void markPublished(UUID id, Instant publishedAt);

    void markFailed(UUID id, Instant nextAttemptAt, String error);

    int deletePublishedBefore(Instant cutoff);

    long countPending();
}
