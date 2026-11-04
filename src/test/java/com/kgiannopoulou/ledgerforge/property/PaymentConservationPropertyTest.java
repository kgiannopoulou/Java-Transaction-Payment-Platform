package com.kgiannopoulou.ledgerforge.property;

import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.Concurrently;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conservation through the whole payment lifecycle, where money also sits in a holds account while
 * payments are in flight. Commands arrive in random order and race each other; most are refused,
 * and refused commands must leave no trace:
 *
 * <pre>
 *   wallets + money held for AUTHORIZED payments = €4000
 * </pre>
 *
 * The rate limit and velocity rule are lifted here: this test is about money, not abuse controls.
 */
@Tag("concurrency")
@TestPropertySource(properties = {
        "ledgerforge.rate-limit.payments.limit=1000000",
        "ledgerforge.risk.rules.velocity.max-attempts=1000000",
        "ledgerforge.risk.rules.repeated-failure.max-failures=1000000"
})
class PaymentConservationPropertyTest extends IntegrationTest {

    private static final BigDecimal TOTAL = new BigDecimal("4000.00");
    private static final int COMMANDS = 3_000;
    private static final int MAX_IN_FLIGHT = 16;

    @Autowired
    JdbcClient jdbc;

    static LongStream seeds() {
        String fixed = System.getProperty("property.seed");
        return fixed != null ? LongStream.of(Long.parseLong(fixed)) : LongStream.of(7L, System.nanoTime());
    }

    private record KnownPayment(UUID id, UUID payer, UUID merchant) {
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void randomConcurrentPaymentCommandsConserveTheTotal(long seed) {
        List<UUID> wallets = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            wallets.add(newFundedWallet("EUR", "1000.00"));
        }
        List<KnownPayment> payments = new CopyOnWriteArrayList<>();
        Random random = new Random(seed);
        // Drawn up front: Random is only deterministic if the threads do not take turns with it.
        List<int[]> plan = new ArrayList<>();
        for (int i = 0; i < COMMANDS; i++) {
            plan.add(new int[]{random.nextInt(6), random.nextInt(1 << 20), random.nextInt(4), random.nextInt(4),
                    1 + random.nextInt(30_000)});
        }

        List<Integer> statuses = Concurrently.run(COMMANDS, MAX_IN_FLIGHT, i -> execute(plan.get(i), wallets, payments));

        String seedNote = "seed " + seed;
        assertThat(statuses).as("%s: no command may crash", seedNote).allMatch(status -> status < 500);
        assertThat(statuses.stream().filter(status -> status == 200).count()).as(seedNote).isGreaterThan(COMMANDS / 10L);

        BigDecimal inWallets = wallets.stream().map(this::balanceOf).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(inWallets.add(heldFor(wallets))).as(seedNote).isEqualByComparingTo(TOTAL);
        assertThat(wallets).as(seedNote).allSatisfy(wallet -> assertThat(balanceOf(wallet).signum()).isNotNegative());
        assertLedgerConsistentFor(wallets);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM payments
                        WHERE payer_account_id = ANY (?) AND refunded_amount > amount""")
                .param(wallets.toArray(UUID[]::new)).query(Long.class).single()).as(seedNote).isZero();
    }

    private int execute(int[] step, List<UUID> wallets, List<KnownPayment> payments) {
        if (step[0] == 0 || payments.isEmpty()) {
            UUID payer = wallets.get(step[2]);
            UUID merchant = wallets.get(step[3] == step[2] ? (step[3] + 1) % 4 : step[3]);
            var response = asOwnerOf(payer).post("/api/v1/payments", Map.of(
                    "payerAccountId", payer, "merchantAccountId", merchant,
                    "amount", cents(step[4]), "currency", "EUR"));
            if (response.status() == 201) {
                payments.add(new KnownPayment(UUID.fromString(response.string("id")), payer, merchant));
            }
            return response.status();
        }
        KnownPayment payment = payments.get(step[1] % payments.size());
        String path = "/api/v1/payments/" + payment.id();
        ApiClient payer = asOwnerOf(payment.payer());
        ApiClient merchant = asOwnerOf(payment.merchant());
        return switch (step[0]) {
            case 1, 2 -> payer.post(path + "/authorize", null).status();
            case 3 -> merchant.post(path + "/capture", null).status();
            case 4 -> payer.post(path + "/cancel", null).status();
            default -> merchant.post(path + "/refund", Map.of("amount", cents(step[4] / 2 + 1), "currency", "EUR")).status();
        };
    }

    private BigDecimal heldFor(List<UUID> wallets) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM payments WHERE payer_account_id = ANY (?) AND status = 'AUTHORIZED'")
                .param(wallets.toArray(UUID[]::new)).query(BigDecimal.class).single();
    }

    private static String cents(int cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
