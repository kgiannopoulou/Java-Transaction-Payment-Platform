package com.kgiannopoulou.ledgerforge.api;

import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyApiTest extends IntegrationTest {

    @Test
    void retryWithSameKeyReturnsOriginalTransferAndMovesMoneyOnce() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID bob = newWallet("EUR");
        String key = "transfer-" + UUID.randomUUID();

        var first = transfer(alice, bob, "50.00", "EUR", key);
        var retry = transfer(alice, bob, "50.00", "EUR", key);
        var retryAgain = transfer(alice, bob, "50.00", "EUR", key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.header("Idempotent-Replayed")).isEmpty();
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.header("Idempotent-Replayed")).contains("true");
        assertThat(retry.string("id")).isEqualTo(first.string("id"));
        assertThat(retryAgain.string("id")).isEqualTo(first.string("id"));

        assertThat(balanceOf(alice)).isEqualByComparingTo("50.00"); // not 0.00
        assertThat(balanceOf(bob)).isEqualByComparingTo("50.00");
    }

    @Test
    void sameKeyWithDifferentBodyIsAConflict() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID bob = newWallet("EUR");
        String key = "transfer-" + UUID.randomUUID();

        assertThat(transfer(alice, bob, "10.00", "EUR", key).status()).isEqualTo(201);
        var reused = transfer(alice, bob, "99.00", "EUR", key);

        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("idempotency_key_reused");
        assertThat(balanceOf(alice)).isEqualByComparingTo("90.00");
    }

    @Test
    void failedRequestDoesNotConsumeTheKey() {
        UUID alice = newFundedWallet("EUR", "10.00");
        UUID bob = newWallet("EUR");
        String key = "transfer-" + UUID.randomUUID();

        assertThat(transfer(alice, bob, "20.00", "EUR", key).code()).isEqualTo("insufficient_funds");
        deposit(alice, "10.00", "EUR");
        // Same key, same body: the earlier failure was rolled back together with the key, so it executes now.
        var retried = transfer(alice, bob, "20.00", "EUR", key);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.header("Idempotent-Replayed")).isEmpty();
        assertThat(balanceOf(alice)).isEqualByComparingTo("0.00");
    }

    @Test
    void keysAreScopedPerOperation() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID bob = newWallet("EUR");
        String key = "shared-" + UUID.randomUUID();

        assertThat(transfer(alice, bob, "1.00", "EUR", key).status()).isEqualTo(201);
        var deposit = api.post("/api/v1/accounts/" + bob + "/deposits", Map.of("amount", "1.00", "currency", "EUR"), key);
        assertThat(deposit.status()).isEqualTo(201);
        assertThat(deposit.header("Idempotent-Replayed")).isEmpty();
    }

    @Test
    void depositRetriesAreDeduplicated() {
        UUID wallet = newWallet("USD");
        String key = "deposit-" + UUID.randomUUID();
        var body = Map.of("amount", "25.00", "currency", "USD");

        var first = api.post("/api/v1/accounts/" + wallet + "/deposits", body, key);
        var second = api.post("/api/v1/accounts/" + wallet + "/deposits", body, key);

        assertThat(second.string("ledgerTransactionId")).isEqualTo(first.string("ledgerTransactionId"));
        assertThat(balanceOf(wallet)).isEqualByComparingTo("25.00");
    }

    @Test
    void rejectsOverlongKeys() {
        UUID alice = newFundedWallet("EUR", "1.00");
        UUID bob = newWallet("EUR");
        var response = transfer(alice, bob, "1.00", "EUR", "k".repeat(256));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("invalid_idempotency_key");
    }
}
