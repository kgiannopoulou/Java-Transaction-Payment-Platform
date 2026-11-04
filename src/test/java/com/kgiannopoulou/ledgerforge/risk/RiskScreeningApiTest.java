package com.kgiannopoulou.ledgerforge.risk;

import com.kgiannopoulou.ledgerforge.security.domain.Role;
import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.Concurrently;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The engine wired into {@code POST /payments}, with real PostgreSQL and Redis. */
class RiskScreeningApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    // ---- the four examples from the plan -----------------------------------------------------

    @Test
    void aSmallPaymentIsAllowed() {
        UUID payer = newWallet("EUR");
        var created = create(payer, newWallet("EUR"), "5.00");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body()).containsEntry("status", "CREATED");
        assertThat(decisionsOf(payer)).singleElement().satisfies(d -> {
            assertThat(d).containsEntry("outcome", "ALLOW").containsEntry("paymentId", created.string("id"));
            assertThat((List<?>) d.get("reasons")).isEmpty();
        });
    }

    @Test
    void aLargePaymentFromANewAccountWaitsForReview() {
        UUID payer = newFundedWallet("EUR", "10000.00");
        var created = create(payer, newWallet("EUR"), "7500.00");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body()).containsEntry("status", "UNDER_REVIEW");
        assertThat(decisionsOf(payer)).singleElement().satisfies(d -> {
            assertThat(d).containsEntry("outcome", "REVIEW");
            assertThat(d.get("reasons").toString()).contains("LARGE_TRANSACTION");
        });
    }

    @Test
    void theSameAmountFromAnEstablishedAccountIsAllowed() {
        UUID payer = newFundedWallet("EUR", "10000.00");
        jdbc.sql("UPDATE accounts SET created_at = now() - interval '1 year' WHERE id = ?").param(payer).update();

        assertThat(create(payer, newWallet("EUR"), "7500.00").body()).containsEntry("status", "CREATED");
    }

    @Test
    void theTwentiethAttemptInThirtySecondsIsBlocked() {
        UUID payer = newWallet("EUR");
        UUID merchant = newWallet("EUR");
        for (int i = 1; i <= 19; i++) {
            assertThat(create(payer, merchant, "1.00").status()).as("attempt %d", i).isEqualTo(201);
        }

        var blocked = create(payer, merchant, "1.00");

        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.code()).isEqualTo("PAYMENT_BLOCKED");
        assertThat(paymentCount(payer)).as("a blocked attempt never becomes a payment").isEqualTo(19);
        assertThat(decisionsOf(payer)).first().satisfies(d -> {
            assertThat(d).containsEntry("outcome", "BLOCK").doesNotContainKey("paymentId");
            assertThat(d.get("reasons").toString()).contains("VELOCITY");
        });
    }

    @Test
    void aFrozenPayerIsBlockedAndTheRefusalIsRecorded() {
        UUID payer = newFundedWallet("EUR", "100.00");
        assertThat(api.post("/api/v1/accounts/" + payer + "/freeze", null).status()).isEqualTo(200);

        var blocked = create(payer, newWallet("EUR"), "5.00");

        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.code()).isEqualTo("PAYMENT_BLOCKED");
        assertThat(decisionsOf(payer)).singleElement().satisfies(d -> {
            assertThat(d).containsEntry("outcome", "BLOCK");
            assertThat(d.get("reasons").toString()).contains("FROZEN_ACCOUNT");
        });
        assertThat(jdbc.sql("SELECT action FROM audit_events WHERE entity_id = ? AND action = 'PAYMENT_BLOCKED'")
                .param(payer).query(String.class).list()).hasSize(1);
    }

    // ---- what a blocked caller learns --------------------------------------------------------

    @Test
    void theRefusalDoesNotRevealWhichRuleFired() {
        UUID payer = newWallet("EUR");
        api.post("/api/v1/accounts/" + payer + "/freeze", null);

        var blocked = create(payer, newWallet("EUR"), "5.00");

        assertThat(blocked.body().toString()).doesNotContainIgnoringCase("frozen").doesNotContain("FROZEN_ACCOUNT");
    }

    // ---- repeated failures -------------------------------------------------------------------

    @Test
    void repeatedDeclinesSendTheNextPaymentToReview() {
        UUID payer = newWallet("EUR"); // no funds, so every authorization fails
        UUID merchant = newWallet("EUR");
        for (int i = 0; i < 5; i++) {
            UUID payment = newPayment(payer, merchant, "1.00", "EUR");
            assertThat(asOwnerOf(payer).post("/api/v1/payments/" + payment + "/authorize", null).code())
                    .isEqualTo("INSUFFICIENT_FUNDS");
        }

        assertThat(create(payer, merchant, "1.00").body()).containsEntry("status", "UNDER_REVIEW");
        assertThat(decisionsOf(payer)).first().satisfies(d ->
                assertThat(d.get("reasons").toString()).contains("REPEATED_FAILURE"));
    }

    // ---- the review workflow -----------------------------------------------------------------

    @Test
    void aPaymentUnderReviewCannotMoveMoney() {
        UUID payer = newFundedWallet("EUR", "10000.00");
        UUID payment = paymentUnderReview(payer);

        var authorize = asOwnerOf(payer).post("/api/v1/payments/" + payment + "/authorize", null);

        assertThat(authorize.status()).isEqualTo(409);
        assertThat(balanceOf(payer)).isEqualByComparingTo("10000.00");
    }

    @Test
    void approvalLetsThePaymentContinueAsUsual() {
        UUID payer = newFundedWallet("EUR", "10000.00");
        UUID payment = paymentUnderReview(payer);

        var approved = as("sam", Role.SUPPORT).post("/api/v1/payments/" + payment + "/review",
                Map.of("decision", "APPROVE", "note", "Verified by phone"));

        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        assertThat(approved.body()).containsEntry("status", "CREATED");
        assertThat(asOwnerOf(payer).post("/api/v1/payments/" + payment + "/authorize", null).body())
                .containsEntry("status", "AUTHORIZED");
        assertThat(balanceOf(payer)).isEqualByComparingTo("2500.00");

        var audit = jdbc.sql("""
                        SELECT actor, metadata->>'note' AS note FROM audit_events
                        WHERE entity_id = ? AND action = 'PAYMENT_REVIEW_APPROVED'""")
                .param(payment).query().singleRow();
        assertThat(audit).containsEntry("actor", "sam").containsEntry("note", "Verified by phone");
    }

    @Test
    void rejectionCancelsThePaymentAndNoMoneyMoves() {
        UUID payer = newFundedWallet("EUR", "10000.00");
        UUID payment = paymentUnderReview(payer);

        var rejected = api.post("/api/v1/payments/" + payment + "/review", Map.of("decision", "REJECT"));

        assertThat(rejected.body()).containsEntry("status", "CANCELLED");
        assertThat(asOwnerOf(payer).post("/api/v1/payments/" + payment + "/authorize", null).status()).isEqualTo(409);
        assertThat(balanceOf(payer)).isEqualByComparingTo("10000.00");
    }

    @Test
    void customersCannotReviewEvenTheirOwnPayment() {
        UUID payer = newFundedWallet("EUR", "10000.00");
        UUID payment = paymentUnderReview(payer);

        assertThat(asOwnerOf(payer).post("/api/v1/payments/" + payment + "/review", Map.of("decision", "APPROVE"))
                .status()).isEqualTo(403);
        assertThat(as("ava", Role.AUDITOR).post("/api/v1/payments/" + payment + "/review", Map.of("decision", "APPROVE"))
                .status()).isEqualTo(403);
    }

    @Test
    void aPaymentNotUnderReviewCannotBeReviewed() {
        UUID payment = newPayment(newWallet("EUR"), newWallet("EUR"), "5.00", "EUR");

        var review = api.post("/api/v1/payments/" + payment + "/review", Map.of("decision", "APPROVE"));

        assertThat(review.status()).isEqualTo(422);
        assertThat(review.code()).isEqualTo("PAYMENT_NOT_UNDER_REVIEW");
    }

    @Test
    void thePayerCanWithdrawAPaymentUnderReview() {
        UUID payer = newFundedWallet("EUR", "10000.00");
        UUID payment = paymentUnderReview(payer);

        assertThat(asOwnerOf(payer).post("/api/v1/payments/" + payment + "/cancel", null).body())
                .containsEntry("status", "CANCELLED");
    }

    @Test
    void theReviewQueueIsForStaff() {
        UUID payer = newFundedWallet("EUR", "10000.00");
        UUID payment = paymentUnderReview(payer);

        var queue = as("sam", Role.SUPPORT).get("/api/v1/risk/decisions?outcome=REVIEW&limit=500");

        assertThat(items(queue)).extracting(d -> d.get("paymentId")).contains(payment.toString());
        assertThat(asOwnerOf(payer).get("/api/v1/risk/decisions").status()).isEqualTo(403);
    }

    // ---- interplay with idempotency and concurrency ------------------------------------------

    @Test
    void anIdempotentReplayIsNotAnotherAttempt() {
        UUID payer = newWallet("EUR");
        UUID merchant = newWallet("EUR");
        ApiClient owner = asOwnerOf(payer);
        Map<String, Object> body = Map.of("payerAccountId", payer, "merchantAccountId", merchant,
                "amount", "1.00", "currency", "EUR");

        for (int i = 0; i < 30; i++) {
            assertThat(owner.post("/api/v1/payments", body, "order-1").status()).isEqualTo(201);
        }

        assertThat(paymentCount(payer)).isEqualTo(1);
        assertThat(decisionsOf(payer)).hasSize(1);
    }

    @Test
    void aBlockedAttemptDoesNotKeepItsIdempotencyKey() {
        UUID payer = newWallet("EUR");
        api.post("/api/v1/accounts/" + payer + "/freeze", null);
        UUID merchant = newWallet("EUR");
        Map<String, Object> body = Map.of("payerAccountId", payer, "merchantAccountId", merchant,
                "amount", "1.00", "currency", "EUR");
        assertThat(asOwnerOf(payer).post("/api/v1/payments", body, "order-2").code()).isEqualTo("PAYMENT_BLOCKED");

        api.post("/api/v1/accounts/" + payer + "/unfreeze", null);

        assertThat(asOwnerOf(payer).post("/api/v1/payments", body, "order-2").status()).isEqualTo(201);
    }

    @Test
    void velocityIsCountedExactlyAcrossConcurrentRequests() {
        UUID payer = newWallet("EUR");
        UUID merchant = newWallet("EUR");
        ApiClient owner = asOwnerOf(payer);

        List<Integer> statuses = Concurrently.run(30, i -> owner.post("/api/v1/payments", Map.of(
                "payerAccountId", payer, "merchantAccountId", merchant, "amount", "1.00", "currency", "EUR")).status());

        assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(19);
        assertThat(statuses.stream().filter(status -> status == 422).count()).isEqualTo(11);
        assertThat(paymentCount(payer)).isEqualTo(19);
    }

    // ---- storage -----------------------------------------------------------------------------

    @Test
    void theDatabaseRefusesABlockThatHasAPayment() {
        UUID payer = newWallet("EUR");
        UUID merchant = newWallet("EUR");
        UUID payment = newPayment(payer, merchant, "5.00", "EUR");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                        "UPDATE risk_decisions SET outcome = 'BLOCK' WHERE payment_id = ?").param(payment).update())
                .rootCause().hasMessageContaining("ck_risk_decisions_blocked_has_no_payment");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private UUID paymentUnderReview(UUID payer) {
        var created = create(payer, newWallet("EUR"), "7500.00");
        assertThat(created.body()).containsEntry("status", "UNDER_REVIEW");
        return UUID.fromString(created.string("id"));
    }

    private ApiClient.Response create(UUID payer, UUID merchant, String amount) {
        return asOwnerOf(payer).post("/api/v1/payments", Map.of(
                "payerAccountId", payer, "merchantAccountId", merchant, "amount", amount, "currency", "EUR"));
    }

    private long paymentCount(UUID payer) {
        return jdbc.sql("SELECT count(*) FROM payments WHERE payer_account_id = ?").param(payer)
                .query(Long.class).single();
    }

    private List<Map<String, Object>> decisionsOf(UUID payer) {
        return items(as("sam", Role.SUPPORT).get("/api/v1/risk/decisions?accountId=" + payer));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ApiClient.Response response) {
        return (List<Map<String, Object>>) response.body().getOrDefault("items", List.of());
    }
}
