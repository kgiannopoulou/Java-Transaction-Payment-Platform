package com.kgiannopoulou.ledgerforge.api;

import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentApiTest extends IntegrationTest {

    private UUID payer;
    private UUID merchant;

    @BeforeEach
    void wallets() {
        payer = newFundedWallet("EUR", "200.00");
        merchant = newWallet("EUR");
    }

    @Test
    void fullLifecycleMovesMoneyThroughHoldsToMerchantAndBack() {
        UUID payment = createPayment("80.00");

        var authorized = command(payment, "authorize");
        assertThat(authorized.body()).containsEntry("status", "AUTHORIZED");
        assertThat(balanceOf(payer)).isEqualByComparingTo("120.00"); // reserved
        assertThat(balanceOf(merchant)).isEqualByComparingTo("0.00");

        assertThat(command(payment, "capture").body()).containsEntry("status", "CAPTURED");
        assertThat(balanceOf(merchant)).isEqualByComparingTo("80.00");

        var partial = refund(payment, "30.00");
        assertThat(partial.body()).containsEntry("status", "PARTIALLY_REFUNDED").containsEntry("refundedAmount", "30.00");
        assertThat(balanceOf(payer)).isEqualByComparingTo("150.00");

        var full = refund(payment, "50.00");
        assertThat(full.body()).containsEntry("status", "REFUNDED");
        assertThat(balanceOf(payer)).isEqualByComparingTo("200.00");
        assertThat(balanceOf(merchant)).isEqualByComparingTo("0.00");

        assertLedgerConsistentFor(List.of(payer, merchant));
    }

    @Test
    void invalidTransitionsAreRejectedWith409() {
        UUID payment = createPayment("10.00");

        var captureBeforeAuth = command(payment, "capture");
        assertThat(captureBeforeAuth.status()).isEqualTo(409);
        assertThat(captureBeforeAuth.code()).isEqualTo("INVALID_PAYMENT_STATE");

        command(payment, "authorize");
        command(payment, "capture");
        refund(payment, "10.00");

        var captureAfterRefund = command(payment, "capture"); // REFUNDED -> CAPTURED
        assertThat(captureAfterRefund.status()).isEqualTo(409);
        assertThat(command(payment, "cancel").status()).isEqualTo(409);
        assertThat(balanceOf(merchant)).isEqualByComparingTo("0.00");
    }

    @Test
    void cancellingAnAuthorizationReleasesTheHold() {
        UUID payment = createPayment("60.00");
        command(payment, "authorize");
        assertThat(balanceOf(payer)).isEqualByComparingTo("140.00");

        assertThat(command(payment, "cancel").body()).containsEntry("status", "CANCELLED");
        assertThat(balanceOf(payer)).isEqualByComparingTo("200.00");
        assertThat(command(payment, "authorize").status()).isEqualTo(409);
    }

    @Test
    void authorizationFailsWithoutFundsAndPaymentStaysCreated() {
        UUID payment = createPayment("200.01");
        var response = command(payment, "authorize");
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(api.get("/api/v1/payments/" + payment).body()).containsEntry("status", "CREATED");
    }

    @Test
    void refundCannotExceedCapturedAmount() {
        UUID payment = createPayment("40.00");
        command(payment, "authorize");
        command(payment, "capture");
        refund(payment, "25.00");

        var tooMuch = refund(payment, "15.01");
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.code()).isEqualTo("REFUND_EXCEEDS_CAPTURED");
    }

    @Test
    void paymentValidation() {
        UUID usd = newWallet("USD");
        var mismatch = api.post("/api/v1/payments", Map.of("payerAccountId", payer, "merchantAccountId", usd,
                "amount", "1.00", "currency", "EUR"));
        assertThat(mismatch.code()).isEqualTo("CURRENCY_MISMATCH");

        var self = api.post("/api/v1/payments", Map.of("payerAccountId", payer, "merchantAccountId", payer,
                "amount", "1.00", "currency", "EUR"));
        assertThat(self.code()).isEqualTo("SAME_ACCOUNT");

        assertThat(command(UUID.randomUUID(), "authorize").status()).isEqualTo(404);
    }

    @Test
    void captureIsIdempotentPerKey() {
        UUID payment = createPayment("10.00");
        command(payment, "authorize");
        String key = "capture-" + UUID.randomUUID();

        var first = api.post("/api/v1/payments/" + payment + "/capture", null, key);
        var retry = api.post("/api/v1/payments/" + payment + "/capture", null, key);

        assertThat(first.status()).isEqualTo(200);
        assertThat(retry.status()).isEqualTo(200); // replayed, not "409 already captured"
        assertThat(retry.header("Idempotent-Replayed")).contains("true");
        assertThat(balanceOf(merchant)).isEqualByComparingTo("10.00");
    }

    private UUID createPayment(String amount) {
        var response = api.post("/api/v1/payments", Map.of(
                "payerAccountId", payer,
                "merchantAccountId", merchant,
                "amount", amount,
                "currency", "EUR",
                "description", "Order #" + UUID.randomUUID()));
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        assertThat(response.body()).containsEntry("status", "CREATED");
        return UUID.fromString(response.string("id"));
    }

    private ApiClient.Response command(UUID payment, String action) {
        return api.post("/api/v1/payments/" + payment + "/" + action, null);
    }

    private ApiClient.Response refund(UUID payment, String amount) {
        return api.post("/api/v1/payments/" + payment + "/refund", Map.of("amount", amount, "currency", "EUR"));
    }
}
