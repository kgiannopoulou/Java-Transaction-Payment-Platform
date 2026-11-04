package com.kgiannopoulou.ledgerforge.concurrency;

import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.Concurrently;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests against the production configuration (PESSIMISTIC locking).
 * Each request goes through real HTTP, Tomcat on virtual threads, Hikari and PostgreSQL.
 */
@Tag("concurrency")
class PessimisticLockingConcurrencyTest extends IntegrationTest {

    @Test
    void moneyIsConservedAcross500ConcurrentRandomTransfers() {
        List<UUID> wallets = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            wallets.add(newFundedWallet("EUR", "100.00")); // 10 x 100 = 1000 EUR in the system
        }

        Random random = new Random(42);
        record Move(UUID from, UUID to, String amount) {
        }
        List<Move> moves = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            int from = random.nextInt(wallets.size());
            int to = (from + 1 + random.nextInt(wallets.size() - 1)) % wallets.size();
            moves.add(new Move(wallets.get(from), wallets.get(to), (1 + random.nextInt(30)) + ".00"));
        }

        List<Integer> statuses = Concurrently.run(moves.size(), 100, i -> {
            Move move = moves.get(i);
            return transfer(move.from(), move.to(), move.amount(), "EUR").status();
        });

        // Every request either succeeded or was cleanly refused for lack of funds - never a 500 or a deadlock.
        assertThat(statuses).containsOnly(201, 422);
        assertThat(statuses).filteredOn(s -> s == 201).hasSizeGreaterThan(250);

        System.out.printf("[race-report] PESSIMISTIC: 500 random transfers -> %d completed, %d refused (insufficient funds)%n",
                statuses.stream().filter(st -> st == 201).count(), statuses.stream().filter(st -> st == 422).count());
        BigDecimal total = wallets.stream().map(this::balanceOf).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("1000.00");
        assertThat(wallets).allSatisfy(w -> assertThat(balanceOf(w)).isNotNegative());
        assertLedgerConsistentFor(wallets);
    }

    @Test
    void doubleSpendIsImpossible() {
        UUID alice = newFundedWallet("EUR", "100.00");
        List<UUID> attackers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            attackers.add(newWallet("EUR"));
        }

        // 100 users simultaneously try to take Alice's entire balance.
        List<ApiClient.Response> responses = Concurrently.run(100,
                i -> transfer(alice, attackers.get(i), "100.00", "EUR"));

        Map<Integer, Long> byStatus = responses.stream()
                .collect(Collectors.groupingBy(ApiClient.Response::status, Collectors.counting()));
        assertThat(byStatus).containsEntry(201, 1L).containsEntry(422, 99L);
        assertThat(responses).filteredOn(r -> r.status() == 422)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS"));
        assertThat(balanceOf(alice)).isEqualByComparingTo("0.00");

        List<UUID> all = new ArrayList<>(attackers);
        all.add(alice);
        assertLedgerConsistentFor(all);
    }

    @Test
    void theNaiveRaceScenarioIsSafeWithRowLocks() {
        // Exactly the attack NaiveLockingRaceTest uses: 20 x 10 EUR out of a 100 EUR wallet.
        UUID alice = newFundedWallet("EUR", "100.00");
        List<UUID> recipients = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            recipients.add(newWallet("EUR"));
        }

        List<Integer> statuses = Concurrently.run(20, i -> transfer(alice, recipients.get(i), "10.00", "EUR").status());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(10);
        assertThat(statuses).filteredOn(s -> s == 422).hasSize(10);
        assertThat(balanceOf(alice)).isEqualByComparingTo("0.00");
        BigDecimal paidOut = recipients.stream().map(this::balanceOf).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(paidOut).isEqualByComparingTo("100.00");
        System.out.printf("[race-report] PESSIMISTIC: 20 x 10.00 from a 100.00 wallet -> 10 succeeded, 10 refused, paid out %s%n", paidOut);
    }

    @Test
    void opposingTransfersDoNotDeadlock() {
        // A->B and B->A at the same time is the textbook deadlock; ordered lock acquisition prevents it.
        UUID a = newFundedWallet("EUR", "1000.00");
        UUID b = newFundedWallet("EUR", "1000.00");

        List<Integer> statuses = Concurrently.run(200,
                i -> (i % 2 == 0 ? transfer(a, b, "3.00", "EUR") : transfer(b, a, "2.00", "EUR")).status());

        assertThat(statuses).containsOnly(201);
        assertThat(balanceOf(a)).isEqualByComparingTo("900.00");  // 1000 - 100*3 + 100*2
        assertThat(balanceOf(b)).isEqualByComparingTo("1100.00");
        assertLedgerConsistentFor(List.of(a, b));
    }

    @Test
    void hundredIdenticalRequestsWithOneIdempotencyKeyMoveMoneyOnce() {
        UUID alice = newFundedWallet("EUR", "500.00");
        UUID bob = newWallet("EUR");
        String key = "retry-storm-" + UUID.randomUUID();

        List<ApiClient.Response> responses = Concurrently.run(100,
                i -> transfer(alice, bob, "50.00", "EUR", key));

        assertThat(responses).extracting(ApiClient.Response::status).containsOnly(201);
        assertThat(responses).extracting(r -> r.string("id")).containsOnly(responses.getFirst().string("id"));
        Map<Boolean, Long> replayed = responses.stream().collect(Collectors.partitioningBy(
                r -> r.header("Idempotent-Replayed").isPresent(), Collectors.counting()));
        assertThat(replayed).containsEntry(false, 1L).containsEntry(true, 99L);

        assertThat(balanceOf(alice)).isEqualByComparingTo("450.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("50.00");
        assertThat(api.get("/api/v1/accounts/" + bob + "/transactions").body()).containsEntry("totalItems", 1);
    }

    @Test
    void concurrentCapturesOfOnePaymentSucceedExactlyOnce() {
        UUID payer = newFundedWallet("EUR", "100.00");
        UUID merchant = newWallet("EUR");
        String paymentId = newPayment(payer, merchant, "75.00", "EUR").toString();
        assertThat(asOwnerOf(payer).post("/api/v1/payments/" + paymentId + "/authorize", null).status()).isEqualTo(200);

        var merchantClient = asOwnerOf(merchant);
        List<Integer> statuses = Concurrently.run(50,
                i -> merchantClient.post("/api/v1/payments/" + paymentId + "/capture", null).status());

        Map<Integer, Long> byStatus = statuses.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(byStatus).containsEntry(200, 1L).containsEntry(409, 49L);
        assertThat(balanceOf(merchant)).isEqualByComparingTo("75.00");
        assertThat(balanceOf(payer)).isEqualByComparingTo("25.00");
    }

    @Test
    void wholeLedgerReconcilesAfterTheStorm() {
        // Runs against everything every other test in this JVM's shared database has written.
        moneyIsConservedAcross500ConcurrentRandomTransfers();
        var report = reconciliation.reconcile();
        assertThat(report.unbalancedTransactions()).isEmpty();
        assertThat(report.accountMismatches()).isEmpty();
        assertThat(report.currencyImbalances()).isEmpty();
        assertThat(report.consistent()).isTrue();
    }
}
