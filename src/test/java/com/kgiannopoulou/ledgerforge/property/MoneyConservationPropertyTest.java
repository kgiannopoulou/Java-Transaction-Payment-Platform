package com.kgiannopoulou.ledgerforge.property;

import com.kgiannopoulou.ledgerforge.support.Concurrently;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invariant that matters most, attacked with random traffic over real HTTP and PostgreSQL:
 *
 * <pre>
 *   A = €1000, B = €1000, C = €1000, D = €1000      total = €4000
 *   thousands of random transfers later, in any order, however many fail:
 *   A + B + C + D = €4000
 * </pre>
 *
 * One fixed seed keeps the build deterministic, and one fresh seed explores new traffic on every
 * run. A failure names its seed; replay it with {@code -Dproperty.seed=<seed>}.
 */
class MoneyConservationPropertyTest extends IntegrationTest {

    private static final BigDecimal TOTAL = new BigDecimal("4000.00");
    private static final int CONCURRENT_TRANSFERS = 5_000;
    private static final int MAX_IN_FLIGHT = 16;
    private static final int MODEL_TRANSFERS = 1_000;

    @Autowired
    JdbcClient jdbc;

    static LongStream seeds() {
        String fixed = System.getProperty("property.seed");
        return fixed != null ? LongStream.of(Long.parseLong(fixed)) : LongStream.of(42L, System.nanoTime());
    }

    private record Move(UUID from, UUID to, String amount) {
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void thousandsOfConcurrentRandomTransfersConserveTheTotal(long seed) {
        List<UUID> wallets = fourWalletsOfAThousand();
        List<Move> moves = randomMoves(new Random(seed), wallets, CONCURRENT_TRANSFERS);

        List<Integer> statuses = Concurrently.run(moves.size(), MAX_IN_FLIGHT, i -> {
            Move move = moves.get(i);
            return transfer(move.from(), move.to(), move.amount(), "EUR").status();
        });

        String seedNote = "seed " + seed;
        assertThat(statuses).as(seedNote).containsOnly(201, 422); // refused is fine; a 5xx or a 409 is not
        long booked = statuses.stream().filter(status -> status == 201).count();
        assertThat(booked).as("%s: the run must really move money", seedNote).isGreaterThan(CONCURRENT_TRANSFERS / 2L);

        assertThat(total(wallets)).as(seedNote).isEqualByComparingTo(TOTAL);
        assertThat(wallets).as(seedNote).allSatisfy(wallet -> assertThat(balanceOf(wallet).signum()).isNotNegative());
        assertLedgerConsistentFor(wallets);
        assertThat(ledgerEntriesFor(wallets)).as("two entries per booked transfer, plus the four deposits")
                .isEqualTo(booked * 2 + 4);
    }

    /**
     * Stricter and sequential: a plain {@code Map} predicts the exact result of each transfer, so
     * this catches money going to the wrong wallet, which conservation alone would not notice.
     */
    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void everyBalanceMatchesASimpleModelAtEveryStep(long seed) {
        List<UUID> wallets = fourWalletsOfAThousand();
        Map<UUID, BigDecimal> model = new HashMap<>();
        wallets.forEach(wallet -> model.put(wallet, new BigDecimal("1000.00")));
        List<Move> moves = randomMoves(new Random(seed), wallets, MODEL_TRANSFERS);

        for (int i = 0; i < moves.size(); i++) {
            Move move = moves.get(i);
            BigDecimal amount = new BigDecimal(move.amount());
            boolean affordable = model.get(move.from()).compareTo(amount) >= 0;

            int status = transfer(move.from(), move.to(), move.amount(), "EUR").status();

            assertThat(status).as("seed %d, step %d: %s", seed, i, move).isEqualTo(affordable ? 201 : 422);
            if (affordable) {
                model.merge(move.from(), amount, BigDecimal::subtract);
                model.merge(move.to(), amount, BigDecimal::add);
            }
            if (i % 100 == 99) {
                wallets.forEach(wallet -> assertThat(balanceOf(wallet)).isEqualByComparingTo(model.get(wallet)));
            }
        }
        assertThat(total(wallets)).isEqualByComparingTo(TOTAL);
        assertLedgerConsistentFor(wallets);
    }

    @Test
    void theWholeLedgerStillReconciles() {
        var report = api.get("/api/v1/admin/reconciliation");

        assertThat(report.body()).containsEntry("consistent", true);
    }

    private List<UUID> fourWalletsOfAThousand() {
        List<UUID> wallets = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            wallets.add(newFundedWallet("EUR", "1000.00"));
        }
        return wallets;
    }

    /** Amounts in whole cents up to €400, so that overdrafts happen regularly too. */
    private static List<Move> randomMoves(Random random, List<UUID> wallets, int count) {
        List<Move> moves = new ArrayList<>(count);
        while (moves.size() < count) {
            UUID from = wallets.get(random.nextInt(wallets.size()));
            UUID to = wallets.get(random.nextInt(wallets.size()));
            if (!from.equals(to)) {
                moves.add(new Move(from, to, BigDecimal.valueOf(1 + random.nextInt(40_000), 2).toPlainString()));
            }
        }
        return moves;
    }

    private BigDecimal total(List<UUID> wallets) {
        return wallets.stream().map(this::balanceOf).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Entries touching these wallets: one per transfer leg, and one per deposit. */
    private long ledgerEntriesFor(List<UUID> wallets) {
        return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE account_id = ANY (?)")
                .param(wallets.toArray(UUID[]::new)).query(Long.class).single();
    }
}
