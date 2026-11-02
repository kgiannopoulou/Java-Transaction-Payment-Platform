package com.kgiannopoulou.ledgerforge.shared.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryingTransactionExecutorTest {

    @Test
    void classifiesConcurrencyFailuresAsRetryable() {
        assertThat(RetryingTransactionExecutor.isRetryable(
                new ObjectOptimisticLockingFailureException("Account", "id"))).isTrue();
        assertThat(RetryingTransactionExecutor.isRetryable(new CannotAcquireLockException("lock"))).isTrue();
        assertThat(RetryingTransactionExecutor.isRetryable(
                new RuntimeException(new SQLException("deadlock detected", "40P01")))).isTrue();
        assertThat(RetryingTransactionExecutor.isRetryable(
                new RuntimeException(new SQLException("could not serialize", "40001")))).isTrue();
    }

    @Test
    void doesNotRetryOtherFailures() {
        assertThat(RetryingTransactionExecutor.isRetryable(new DataIntegrityViolationException("dup"))).isFalse();
        assertThat(RetryingTransactionExecutor.isRetryable(new IllegalStateException("bug"))).isFalse();
        assertThat(RetryingTransactionExecutor.isRetryable(
                new RuntimeException(new SQLException("unique", "23505")))).isFalse();
    }

    @Test
    void backoffIsBoundedAndGrows() {
        var properties = new ConcurrencyProperties(LockingMode.OPTIMISTIC, 8,
                Duration.ofMillis(10), Duration.ofMillis(200), Duration.ZERO);
        var executor = new RetryingTransactionExecutor(null, properties);
        for (int attempt = 1; attempt <= 20; attempt++) {
            assertThat(executor.backoff(attempt)).isBetween(Duration.ZERO, Duration.ofMillis(200));
        }
    }
}
