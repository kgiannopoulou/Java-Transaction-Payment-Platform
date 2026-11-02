package com.kgiannopoulou.ledgerforge.shared.transaction;

import com.kgiannopoulou.ledgerforge.shared.error.ConflictException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import org.hibernate.StaleStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Runs a unit of work in a fresh database transaction and transparently retries it when it fails
 * for a <em>transient concurrency</em> reason:
 *
 * <ul>
 *   <li>optimistic-lock conflicts ({@code @Version} mismatch),</li>
 *   <li>deadlocks ({@code SQLSTATE 40P01}) and serialization failures ({@code 40001}),</li>
 *   <li>lock acquisition timeouts ({@code 55P03}).</li>
 * </ul>
 *
 * <p>Retrying is only safe because the whole unit of work - idempotency-key bookkeeping, balance
 * checks, ledger entries - runs inside the transaction that is rolled back. Business failures
 * (insufficient funds, frozen account, ...) are never retried.
 */
@Component
public class RetryingTransactionExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryingTransactionExecutor.class);
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40001", "40P01", "55P03");

    private final TransactionTemplate transactionTemplate;
    private final ConcurrencyProperties properties;
    private final LongAdder retries = new LongAdder();

    public RetryingTransactionExecutor(PlatformTransactionManager transactionManager, ConcurrencyProperties properties) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    public <T> T execute(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (!isRetryable(e)) {
                    throw e;
                }
                if (attempt >= properties.maxAttempts()) {
                    log.warn("Giving up after {} attempts due to concurrent modification: {}", attempt, e.toString());
                    throw new ConflictException("concurrent_modification",
                            "The resource was modified concurrently; please retry the request");
                }
                retries.increment();
                log.debug("Retryable concurrency failure on attempt {}: {}", attempt, e.toString());
                sleep(backoff(attempt));
            }
        }
    }

    /** Total number of retries performed since startup (exposed for tests and, later, metrics). */
    public long retryCount() {
        return retries.sum();
    }

    /** Full-jitter exponential backoff: uniform in [0, min(max, initial * 2^(attempt-1))]. */
    Duration backoff(int attempt) {
        long cap = properties.maxBackoff().toMillis();
        long exponential = properties.initialBackoff().toMillis() << Math.min(attempt - 1, 20);
        long upper = Math.max(1, Math.min(cap, exponential));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(upper + 1));
    }

    static boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof OptimisticLockingFailureException
                    || t instanceof PessimisticLockingFailureException
                    || t instanceof OptimisticLockException
                    || t instanceof PessimisticLockException
                    || t instanceof LockTimeoutException
                    || t instanceof StaleStateException) {
                return true;
            }
            if (t instanceof SQLException sql && RETRYABLE_SQL_STATES.contains(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", e);
        }
    }
}
