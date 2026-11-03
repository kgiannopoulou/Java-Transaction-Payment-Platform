package com.kgiannopoulou.ledgerforge.concurrency;

import com.kgiannopoulou.ledgerforge.shared.transaction.RetryingTransactionExecutor;
import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.Concurrently;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same attacks with {@code @Version}-based optimistic locking. Conflicting writers fail at flush
 * time ({@code UPDATE ... WHERE version = ?} matches 0 rows) and the whole transaction is retried.
 * Correctness is identical to pessimistic locking; the cost moves from waiting to retrying.
 */
@TestPropertySource(properties = {
        "ledgerforge.concurrency.locking-mode=OPTIMISTIC",
        "ledgerforge.concurrency.max-attempts=50",
        "ledgerforge.concurrency.simulated-latency=5ms"
})
class OptimisticLockingConcurrencyTest extends IntegrationTest {

    @Autowired
    RetryingTransactionExecutor executor;

    @Test
    void contendedTransfersAreRetriedAndStayCorrect() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID bob = newWallet("EUR");
        long retriesBefore = executor.retryCount();

        List<ApiClient.Response> responses = Concurrently.run(40, i -> transfer(alice, bob, "1.00", "EUR"));

        assertThat(responses).extracting(ApiClient.Response::status).containsOnly(201);
        assertThat(balanceOf(alice)).isEqualByComparingTo("60.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("40.00");
        System.out.printf("[race-report] OPTIMISTIC: 40/40 transfers succeeded after %d retries%n",
                executor.retryCount() - retriesBefore);
        assertThat(executor.retryCount() - retriesBefore)
                .as("optimistic conflicts must have happened and been retried")
                .isPositive();
        assertLedgerConsistentFor(List.of(alice, bob));
    }

    @Test
    void doubleSpendIsStillImpossible() {
        UUID alice = newFundedWallet("EUR", "100.00");
        List<UUID> attackers = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            attackers.add(newWallet("EUR"));
        }

        List<ApiClient.Response> responses = Concurrently.run(30,
                i -> transfer(alice, attackers.get(i), "100.00", "EUR"));

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() != 201)
                .allSatisfy(r -> assertThat(r.code()).isIn("INSUFFICIENT_FUNDS", "CONCURRENT_MODIFICATION"));
        assertThat(balanceOf(alice)).isEqualByComparingTo("0.00");
    }
}
