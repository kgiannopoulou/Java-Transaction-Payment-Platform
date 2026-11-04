package com.kgiannopoulou.ledgerforge.redis;

import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStore;
import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.Concurrently;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Rate limits, the cache and the sliding-window primitive, against a real Redis. */
class RedisBackedFeaturesTest extends IntegrationTest {

    private static final int LIMIT = 100;

    @Autowired
    EphemeralStore store;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    // ---- rate limit on POST /payments --------------------------------------------------------

    @Test
    void the101stPaymentInAMinuteGets429WithRetryAfter() {
        UUID payer = newWallet("EUR");
        UUID merchant = newWallet("EUR");
        for (int i = 0; i < LIMIT; i++) {
            assertThat(createPayment(payer, merchant).status()).as("request %d", i + 1).isNotEqualTo(429);
        }

        var limited = createPayment(payer, merchant);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(limited.string("traceId")).isNotBlank();
        assertThat(limited.header("Retry-After")).isPresent();
        assertThat(Integer.parseInt(limited.header("Retry-After").orElseThrow())).isBetween(1, 60);
    }

    @Test
    void theLimitIsPerAccount() {
        UUID busy = newWallet("EUR");
        UUID quiet = newWallet("EUR");
        UUID merchant = newWallet("EUR");
        for (int i = 0; i < LIMIT; i++) {
            createPayment(busy, merchant);
        }

        assertThat(createPayment(busy, merchant).status()).isEqualTo(429);
        assertThat(createPayment(quiet, merchant).status()).isEqualTo(201);
    }

    @Test
    void concurrentRequestsCannotOvershootTheLimit() {
        UUID payer = newWallet("EUR");
        UUID merchant = newWallet("EUR");
        ApiClient owner = asOwnerOf(payer);

        List<Integer> statuses = Concurrently.run(150, 50, i -> owner.post("/api/v1/payments", Map.of(
                "payerAccountId", payer, "merchantAccountId", merchant, "amount", "1.00", "currency", "EUR")).status());

        assertThat(statuses.stream().filter(status -> status == 429).count()).isEqualTo(50);
    }

    @Test
    void aCallerCannotBurnSomeoneElsesAllowance() {
        UUID victim = newWallet("EUR");
        UUID attacker = newWallet("EUR");
        UUID merchant = newWallet("EUR");
        for (int i = 0; i < LIMIT + 5; i++) {
            assertThat(asOwnerOf(attacker).post("/api/v1/payments", Map.of(
                    "payerAccountId", victim, "merchantAccountId", merchant, "amount", "1.00", "currency", "EUR"))
                    .status()).isEqualTo(403);
        }

        assertThat(createPayment(victim, merchant).status()).isEqualTo(201);
    }

    // ---- the sliding window ------------------------------------------------------------------

    @Test
    void hitsLeaveTheWindowAsTimePasses() {
        String key = key();
        Duration window = Duration.ofMillis(400);

        assertThat(store.tryAcquire(key, 2, window).allowed()).isTrue();
        assertThat(store.tryAcquire(key, 2, window).allowed()).isTrue();
        var denied = store.tryAcquire(key, 2, window);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isPositive().isLessThanOrEqualTo(window);

        await().atMost(Duration.ofSeconds(3)).until(() -> store.tryAcquire(key, 2, window).allowed());
    }

    @Test
    void deniedHitsDoNotExtendTheWindow() {
        String key = key();
        Duration window = Duration.ofSeconds(30);
        store.tryAcquire(key, 1, window);

        store.tryAcquire(key, 1, window);
        store.tryAcquire(key, 1, window);

        assertThat(store.count(key, window)).isEqualTo(1);
    }

    @Test
    void incrementCountsEveryHitAcrossConcurrentCallers() {
        String key = key();
        Duration window = Duration.ofMinutes(1);

        List<Long> counts = Concurrently.run(200, 50, i -> store.increment(key, window));

        assertThat(store.count(key, window)).isEqualTo(200);
        assertThat(counts).doesNotHaveDuplicates().contains(1L, 200L);
    }

    // ---- the cache ---------------------------------------------------------------------------

    @Test
    void ownershipIsServedFromTheCacheAfterTheFirstLookup() {
        UUID wallet = newWallet("EUR");
        ApiClient owner = asOwnerOf(wallet);
        double hitsBefore = cacheCount("hit");

        assertThat(owner.get("/api/v1/accounts/" + wallet).status()).isEqualTo(200);
        assertThat(owner.get("/api/v1/accounts/" + wallet).status()).isEqualTo(200);

        assertThat(redis.opsForValue().get("cache:account-owner:" + wallet)).isEqualTo(ownerOf(wallet).toString());
        assertThat(redis.getExpire("cache:account-owner:" + wallet, TimeUnit.SECONDS)).isBetween(1L, 300L);
        assertThat(cacheCount("hit")).isGreaterThan(hitsBefore);
    }

    @Test
    void unknownAccountsAreNotCached() {
        UUID unknown = UUID.randomUUID();

        assertThat(asCustomer(newCustomer()).get("/api/v1/accounts/" + unknown).status()).isEqualTo(403);

        assertThat(redis.hasKey("cache:account-owner:" + unknown)).isFalse();
    }

    // ---- what is in Redis --------------------------------------------------------------------

    @Test
    void redisHoldsNoBalancesAndNothingWithoutAnExpiry() {
        UUID payer = newFundedWallet("EUR", "100.00");
        UUID merchant = newWallet("EUR");
        UUID payment = newPayment(payer, merchant, "30.00", "EUR");
        asOwnerOf(payer).post("/api/v1/payments/" + payment + "/authorize", null);
        asOwnerOf(merchant).post("/api/v1/payments/" + payment + "/capture", null);

        var keys = redis.keys("*");

        assertThat(keys).isNotEmpty();
        assertThat(keys).allSatisfy(key -> {
            assertThat(key).doesNotContainIgnoringCase("balance").doesNotContainIgnoringCase("ledger");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).as("expiry of %s", key).isNotEqualTo(-1);
        });
    }

    // ---- helpers -----------------------------------------------------------------------------

    private ApiClient.Response createPayment(UUID payer, UUID merchant) {
        return asOwnerOf(payer).post("/api/v1/payments", Map.of(
                "payerAccountId", payer, "merchantAccountId", merchant, "amount", "1.00", "currency", "EUR"));
    }

    private double cacheCount(String result) {
        return meters.counter("ledgerforge.cache.requests", "cache", "account-owner", "result", result).count();
    }

    private static String key() {
        return "test:" + UUID.randomUUID();
    }
}
