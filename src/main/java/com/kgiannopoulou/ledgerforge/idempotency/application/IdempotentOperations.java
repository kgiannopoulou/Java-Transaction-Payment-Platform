package com.kgiannopoulou.ledgerforge.idempotency.application;

import com.kgiannopoulou.ledgerforge.idempotency.domain.IdempotencyKey;
import com.kgiannopoulou.ledgerforge.idempotency.domain.IdempotencyRecord;
import com.kgiannopoulou.ledgerforge.idempotency.domain.IdempotencyRepository;
import com.kgiannopoulou.ledgerforge.shared.error.ConflictException;
import com.kgiannopoulou.ledgerforge.shared.error.InvalidRequestException;
import com.kgiannopoulou.ledgerforge.shared.transaction.RetryingTransactionExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Executes a state-changing operation at most once per {@code (scope, Idempotency-Key)}.
 *
 * <p>Algorithm, all inside ONE database transaction (retried as a whole on lock conflicts):
 * <ol>
 *   <li>{@code INSERT ... ON CONFLICT DO NOTHING} the key as {@code IN_PROGRESS}. A concurrent
 *       duplicate blocks here until the first transaction commits or rolls back.</li>
 *   <li>{@code SELECT ... FOR UPDATE} the key row.</li>
 *   <li>Different request fingerprint &rarr; {@code 409 idempotency_key_reused}.</li>
 *   <li>{@code COMPLETED} &rarr; replay the stored response; the operation is not executed.</li>
 *   <li>Otherwise run the operation, store its response, mark {@code COMPLETED}, commit.</li>
 * </ol>
 *
 * <p>Because the key and the money movement commit atomically, there is no window in which one
 * exists without the other. If the operation fails with a business error the transaction (and
 * the key) is rolled back, so the client may retry with the same key after fixing the cause.
 */
@Service
public class IdempotentOperations {

    private static final Logger log = LoggerFactory.getLogger(IdempotentOperations.class);
    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyRepository repository;
    private final RetryingTransactionExecutor transactions;
    private final JsonMapper jsonMapper;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public IdempotentOperations(IdempotencyRepository repository, RetryingTransactionExecutor transactions,
                                JsonMapper jsonMapper, IdempotencyProperties properties, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param scope        namespace of the operation, e.g. {@code "transfer"}
     * @param key          client-supplied {@code Idempotency-Key}; {@code null} executes without idempotency
     * @param request      everything that identifies the request (body + path ids); fingerprinted as JSON
     * @param responseType type used to deserialise a replayed response
     * @param status       HTTP status of a successful response
     * @param operation    the business operation; runs inside the transaction
     */
    public <T> IdempotentResult<T> execute(String scope, @Nullable String key, Object request,
                                           Class<T> responseType, int status, Supplier<T> operation) {
        if (key == null) {
            return transactions.execute(() -> IdempotentResult.fresh(operation.get(), status));
        }
        validateKey(key);
        String fingerprint = RequestFingerprint.sha256(scope, jsonMapper.writeValueAsBytes(request));
        return transactions.execute(() -> executeOnce(new IdempotencyKey(scope, key), fingerprint,
                responseType, status, operation));
    }

    private <T> IdempotentResult<T> executeOnce(IdempotencyKey id, String fingerprint, Class<T> responseType,
                                                int status, Supplier<T> operation) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.ttl());
        repository.insertIfAbsent(id.scope(), id.key(), fingerprint, now, expiresAt);
        IdempotencyRecord record = repository.findForUpdate(id)
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished: " + id));

        if (record.isCompleted() && record.isExpired(now)) {
            record.restart(fingerprint, now, expiresAt);
        }
        if (!record.matches(fingerprint)) {
            throw new ConflictException("idempotency_key_reused",
                    "Idempotency-Key '" + id.key() + "' was already used with a different request");
        }
        if (record.isCompleted()) {
            log.debug("Replaying stored response for idempotency key {}", id);
            return IdempotentResult.replay(jsonMapper.readValue(record.getResponseBody(), responseType),
                    record.getResponseCode());
        }

        T response = operation.get();
        record.complete(status, jsonMapper.writeValueAsString(response), clock.instant());
        return IdempotentResult.fresh(response, status);
    }

    private static void validateKey(String key) {
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new InvalidRequestException("invalid_idempotency_key",
                    "Idempotency-Key must be 1-" + MAX_KEY_LENGTH + " non-blank characters");
        }
    }

    @Scheduled(fixedDelayString = "${ledgerforge.idempotency.cleanup-interval:PT1H}",
            initialDelayString = "${ledgerforge.idempotency.cleanup-interval:PT1H}")
    @Transactional
    public void purgeExpiredKeys() {
        int deleted = repository.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("Purged {} expired idempotency keys", deleted);
        }
    }
}
