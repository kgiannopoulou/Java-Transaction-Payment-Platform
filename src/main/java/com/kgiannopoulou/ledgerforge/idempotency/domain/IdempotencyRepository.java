package com.kgiannopoulou.ledgerforge.idempotency.domain;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRepository {

    /**
     * {@code INSERT ... ON CONFLICT DO NOTHING}. If another transaction has inserted the same key
     * but not committed yet, PostgreSQL blocks this statement until that transaction finishes,
     * which is exactly what serialises concurrent duplicates.
     *
     * @return 1 if a new row was inserted, 0 if the key already existed
     */
    int insertIfAbsent(String scope, String key, String requestHash, Instant createdAt, Instant expiresAt);

    /** {@code SELECT ... FOR UPDATE} on the key row. */
    Optional<IdempotencyRecord> findForUpdate(IdempotencyKey id);

    int deleteExpired(Instant now);
}
