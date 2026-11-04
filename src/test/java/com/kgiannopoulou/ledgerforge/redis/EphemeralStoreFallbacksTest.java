package com.kgiannopoulou.ledgerforge.redis;

import com.kgiannopoulou.ledgerforge.account.domain.AccountRepository;
import com.kgiannopoulou.ledgerforge.ratelimit.application.RateLimitProperties;
import com.kgiannopoulou.ledgerforge.ratelimit.application.RateLimiter;
import com.kgiannopoulou.ledgerforge.risk.application.RiskProperties;
import com.kgiannopoulou.ledgerforge.risk.application.RiskSignals;
import com.kgiannopoulou.ledgerforge.security.application.AccountOwners;
import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStore;
import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStore.Acquisition;
import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStoreUnavailableException;
import com.kgiannopoulou.ledgerforge.shared.error.TooManyRequestsException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Where a mock genuinely helps: each caller's reaction to the store failing at one exact moment,
 * such as the read working and the write failing. A real Redis cannot be made to do that on cue.
 * {@code RedisOutageTest} covers a real outage end to end.
 */
@ExtendWith(MockitoExtension.class)
class EphemeralStoreFallbacksTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final EphemeralStoreUnavailableException DOWN =
            new EphemeralStoreUnavailableException(new RuntimeException("connection refused"));

    @Mock
    EphemeralStore store;

    @Mock
    AccountRepository accounts;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    // ---- rate limiter ------------------------------------------------------------------------

    private RateLimiter rateLimiter() {
        return new RateLimiter(store, new RateLimitProperties(new RateLimitProperties.Policy(100, Duration.ofMinutes(1))), meters);
    }

    @Test
    void rateLimiterFailsOpenAndCountsIt() {
        when(store.tryAcquire(anyString(), anyInt(), any())).thenThrow(DOWN);

        assertThatCode(() -> rateLimiter().checkPaymentCreation(ACCOUNT)).doesNotThrowAnyException();

        assertThat(meters.counter("ledgerforge.ratelimit.decisions", "operation", "payments", "result", "unavailable")
                .count()).isEqualTo(1);
    }

    @Test
    void rateLimiterPassesRetryAfterOn() {
        when(store.tryAcquire(anyString(), anyInt(), any()))
                .thenReturn(new Acquisition(false, 100, Duration.ofSeconds(37)));

        assertThatThrownBy(() -> rateLimiter().checkPaymentCreation(ACCOUNT))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(37)));
    }

    // ---- ownership cache ---------------------------------------------------------------------

    private AccountOwners owners() {
        return new AccountOwners(accounts, store, meters);
    }

    @Test
    void aCacheHitNeverReachesTheDatabase() {
        when(store.get(anyString())).thenReturn(Optional.of(OWNER.toString()));

        assertThat(owners().ownerOf(ACCOUNT)).contains(OWNER);

        verifyNoInteractions(accounts);
    }

    @Test
    void aMissReadsThePostgresqlAnswerAndCachesIt() {
        when(store.get(anyString())).thenReturn(Optional.empty());
        when(accounts.findCustomerIdById(ACCOUNT)).thenReturn(Optional.of(OWNER));

        assertThat(owners().ownerOf(ACCOUNT)).contains(OWNER);

        verify(store).put(eq("cache:account-owner:" + ACCOUNT), eq(OWNER.toString()), any());
    }

    @Test
    void anUnknownAccountIsNotCached() {
        when(store.get(anyString())).thenReturn(Optional.empty());
        when(accounts.findCustomerIdById(ACCOUNT)).thenReturn(Optional.empty());

        assertThat(owners().ownerOf(ACCOUNT)).isEmpty();

        verify(store, never()).put(anyString(), anyString(), any());
    }

    @Test
    void aFailedCacheReadFallsBackToPostgresql() {
        when(store.get(anyString())).thenThrow(DOWN);
        when(accounts.findCustomerIdById(ACCOUNT)).thenReturn(Optional.of(OWNER));

        assertThat(owners().ownerOf(ACCOUNT)).contains(OWNER);
    }

    @Test
    void aFailedCacheWriteDoesNotFailTheRequest() {
        when(store.get(anyString())).thenReturn(Optional.empty());
        when(accounts.findCustomerIdById(ACCOUNT)).thenReturn(Optional.of(OWNER));
        doThrow(DOWN).when(store).put(anyString(), anyString(), any());

        assertThat(owners().ownerOf(ACCOUNT)).contains(OWNER);
    }

    // ---- risk signals ------------------------------------------------------------------------

    private RiskSignals signals() {
        var properties = new RiskProperties(BigDecimal.TEN, new RiskProperties.Rules(
                new RiskProperties.Velocity(20, Duration.ofSeconds(30)),
                new RiskProperties.LargeTransaction(BigDecimal.TEN, Duration.ofDays(30)),
                new RiskProperties.RepeatedFailure(5, Duration.ofMinutes(10))));
        return new RiskSignals(store, properties, meters);
    }

    @Test
    void signalsReportThemselvesUnavailableRatherThanPretendingToBeZero() {
        when(store.increment(anyString(), any())).thenThrow(DOWN);

        assertThat(signals().recordAttempt(ACCOUNT).available()).isFalse();
    }

    @Test
    void aFailureAfterTheCountIsStillUnavailable() {
        when(store.increment(anyString(), any())).thenReturn(1L);
        when(store.count(anyString(), any())).thenThrow(DOWN); // half a snapshot is not a snapshot

        assertThat(signals().recordAttempt(ACCOUNT).available()).isFalse();
    }

    @Test
    void recordingAFailureNeverThrows() {
        when(store.increment(anyString(), any())).thenThrow(DOWN);

        assertThatCode(() -> signals().recordFailure(ACCOUNT)).doesNotThrowAnyException();
    }
}
