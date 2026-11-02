package com.kgiannopoulou.ledgerforge.api;

import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferApiTest extends IntegrationTest {

    @Test
    void aliceSendsBobTwentyEuros() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID bob = newWallet("EUR");

        var response = transfer(alice, bob, "20.00", "EUR");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).containsEntry("status", "COMPLETED")
                .containsEntry("amount", "20.00")
                .containsEntry("currency", "EUR");
        assertThat(balanceOf(alice)).isEqualByComparingTo("80.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("20.00");

        // The transfer is backed by exactly one balanced ledger transaction: DEBIT Alice, CREDIT Bob.
        var ledgerTx = api.get("/api/v1/ledger/transactions/" + response.string("ledgerTransactionId"));
        assertThat(ledgerTx.body()).containsEntry("type", "TRANSFER")
                .containsEntry("reference", "TRF-" + response.string("id"));
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) ledgerTx.body().get("entries");
        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(e -> assertThat(e)
                .containsEntry("accountId", alice.toString()).containsEntry("direction", "DEBIT"));
        assertThat(entries).anySatisfy(e -> assertThat(e)
                .containsEntry("accountId", bob.toString()).containsEntry("direction", "CREDIT"));

        assertThat(api.get("/api/v1/transfers/" + response.string("id")).body()).containsEntry("status", "COMPLETED");
        assertLedgerConsistentFor(List.of(alice, bob));
    }

    @Test
    void insufficientFundsRollsBackEverything() {
        UUID alice = newFundedWallet("EUR", "10.00");
        UUID bob = newWallet("EUR");

        var response = transfer(alice, bob, "10.01", "EUR");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("insufficient_funds");
        assertThat(balanceOf(alice)).isEqualByComparingTo("10.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("0.00");
        assertThat(api.get("/api/v1/accounts/" + bob + "/transactions").body()).containsEntry("totalItems", 0);
    }

    @Test
    void rejectsTransferToSameAccount() {
        UUID alice = newFundedWallet("EUR", "10.00");
        var response = transfer(alice, alice, "1.00", "EUR");
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("same_account");
    }

    @Test
    void rejectsUnknownAccounts() {
        UUID alice = newFundedWallet("EUR", "10.00");
        var response = transfer(alice, UUID.randomUUID(), "1.00", "EUR");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("account_not_found");
    }

    @Test
    void rejectsFrozenAccounts() {
        UUID alice = newFundedWallet("EUR", "10.00");
        UUID bob = newWallet("EUR");
        api.post("/api/v1/accounts/" + bob + "/freeze", null);

        var response = transfer(alice, bob, "1.00", "EUR");
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("account_not_active");
        assertThat(balanceOf(alice)).isEqualByComparingTo("10.00");
    }

    @Test
    void rejectsCurrencyMismatches() {
        UUID eur = newFundedWallet("EUR", "10.00");
        UUID usd = newWallet("USD");
        UUID otherEur = newWallet("EUR");

        assertThat(transfer(eur, usd, "1.00", "EUR").code()).isEqualTo("currency_mismatch");
        assertThat(transfer(eur, otherEur, "1.00", "USD").code()).isEqualTo("currency_mismatch");
    }

    @Test
    void rejectsSystemAccounts() {
        UUID alice = newFundedWallet("EUR", "10.00");
        UUID holds = UUID.fromString("00000000-0000-0000-0000-00000000a001");
        var response = transfer(alice, holds, "1.00", "EUR");
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("not_a_wallet");
    }

    @Test
    void validatesAmounts() {
        UUID alice = newFundedWallet("EUR", "10.00");
        UUID bob = newWallet("EUR");
        assertThat(transfer(alice, bob, "0", "EUR").status()).isEqualTo(400);
        assertThat(transfer(alice, bob, "-1.00", "EUR").status()).isEqualTo(400);
        var precision = transfer(alice, bob, "0.001", "EUR");
        assertThat(precision.status()).isEqualTo(400);
        assertThat(precision.code()).isEqualTo("invalid_amount");
        assertThat(api.post("/api/v1/transfers", "not json").status()).isEqualTo(400);
    }
}
