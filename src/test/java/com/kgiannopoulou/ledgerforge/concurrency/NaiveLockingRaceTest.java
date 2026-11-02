package com.kgiannopoulou.ledgerforge.concurrency;

import com.kgiannopoulou.ledgerforge.ledger.application.LedgerReconciliationService.AccountMismatch;
import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.Concurrently;
import com.kgiannopoulou.ledgerforge.support.ApiTestSupport;
import com.kgiannopoulou.ledgerforge.support.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the bug that the locking strategies exist to prevent.
 *
 * <p>With {@code NAIVE} mode every request reads Alice's balance without a lock, sleeps briefly
 * (simulating real work and widening the race window) and then blindly writes
 * {@code balance = read - amount}. Concurrent requests read the same starting balance, so all of
 * them "succeed" and the last writer wins:
 *
 * <pre>
 *   Thread A reads 100        Thread B reads 100
 *   Thread A writes 90        Thread B writes 90     -> one 10 EUR debit is lost
 * </pre>
 *
 * <p>Uses its own PostgreSQL container: the corruption it creates on purpose must not leak into
 * the whole-ledger reconciliation run by other tests.
 */
@TestPropertySource(properties = {
        "ledgerforge.concurrency.locking-mode=NAIVE",
        "ledgerforge.concurrency.simulated-latency=100ms"
})
class NaiveLockingRaceTest extends ApiTestSupport {

    private static final PostgreSQLContainer ISOLATED = PostgresContainers.start();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        PostgresContainers.register(registry, ISOLATED);
    }

    @Test
    void naiveReadModifyWriteLosesUpdatesAndOverspends() {
        UUID alice = newFundedWallet("EUR", "100.00");
        List<UUID> recipients = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            recipients.add(newWallet("EUR"));
        }

        // 20 concurrent withdrawals of 10 EUR from a 100 EUR wallet: at most 10 can be honoured.
        List<ApiClient.Response> responses = Concurrently.run(20,
                i -> transfer(alice, recipients.get(i), "10.00", "EUR"));
        long succeeded = responses.stream().filter(r -> r.status() == 201).count();

        BigDecimal paidOut = recipients.stream().map(this::balanceOf).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal aliceBalance = balanceOf(alice);

        // The anomaly: more than 10 transfers "succeeded", recipients received more than Alice ever had,
        // and Alice's stored balance no longer matches her ledger entries.
        assertThat(succeeded).as("lost updates let more than 10 transfers through").isGreaterThan(10);
        assertThat(paidOut).as("money was created out of thin air").isGreaterThan(new BigDecimal("100.00"));
        assertThat(aliceBalance.add(paidOut)).isGreaterThan(new BigDecimal("100.00"));

        List<AccountMismatch> mismatches = reconciliation.mismatchesFor(List.of(alice));
        System.out.printf("[race-report] NAIVE: %d/20 transfers succeeded, recipients received %s from a 100.00 wallet;"
                        + " stored balance %s vs ledger balance %s%n",
                succeeded, paidOut, aliceBalance, mismatches.isEmpty() ? "n/a" : mismatches.getFirst().ledgerBalance());
        assertThat(mismatches).as("stored balance diverged from the ledger").hasSize(1);
        assertThat(mismatches.getFirst().ledgerBalance()).isLessThan(mismatches.getFirst().storedBalance());
        assertThat(reconciliation.reconcile().consistent()).isFalse();
    }
}
