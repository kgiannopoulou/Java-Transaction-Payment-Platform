package com.kgiannopoulou.ledgerforge.redis;

import com.kgiannopoulou.ledgerforge.support.ApiTestSupport;
import com.kgiannopoulou.ledgerforge.support.PostgresContainers;
import com.kgiannopoulou.ledgerforge.support.RedisContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The design decision, tested: Redis is optional. This test owns a Redis container and kills it
 * mid-test, and the money path must keep working. PostgreSQL remains the source of truth.
 */
class RedisOutageTest extends ApiTestSupport {

    private static final GenericContainer<?> REDIS = RedisContainers.start();

    @Autowired
    JdbcClient jdbc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        PostgresContainers.register(registry, PostgresContainers.SHARED);
        RedisContainers.register(registry, REDIS);
    }

    @Test
    void paymentsKeepWorkingWhenRedisGoesDown() {
        UUID payer = newFundedWallet("EUR", "100.00");
        UUID merchant = newWallet("EUR");
        assertThat(createPayment(payer, merchant).status()).as("with Redis up").isEqualTo(201);

        REDIS.stop();

        long started = System.nanoTime();
        var created = createPayment(payer, merchant);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        assertThat(elapsed).as("timeouts must keep an outage from hanging requests").isLessThan(Duration.ofSeconds(5));

        // The whole money path, with ownership checks falling back to PostgreSQL.
        UUID payment = UUID.fromString(created.string("id"));
        assertThat(asOwnerOf(payer).post("/api/v1/payments/" + payment + "/authorize", null).status()).isEqualTo(200);
        assertThat(asOwnerOf(merchant).post("/api/v1/payments/" + payment + "/capture", null).status()).isEqualTo(200);
        assertThat(balanceOf(payer)).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(balanceOf(merchant)).isEqualByComparingTo(new BigDecimal("10.00"));
        assertLedgerConsistentFor(java.util.List.of(payer, merchant));

        assertThat(anonymous.get("/actuator/health/readiness").status()).as("still ready for traffic").isEqualTo(200);

        // The rules that need Redis could not run, and the permanent record says so.
        assertThat(jdbc.sql("SELECT degraded FROM risk_decisions WHERE payment_id = ?").param(payment)
                .query(Boolean.class).single()).isTrue();
    }

    private com.kgiannopoulou.ledgerforge.support.ApiClient.Response createPayment(UUID payer, UUID merchant) {
        return asOwnerOf(payer).post("/api/v1/payments", Map.of(
                "payerAccountId", payer, "merchantAccountId", merchant, "amount", "10.00", "currency", "EUR"));
    }
}
