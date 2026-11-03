package com.kgiannopoulou.ledgerforge.api;

import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Every error, domain or framework, has the same shape and never leaks internals. */
class ErrorResponseApiTest extends IntegrationTest {

    @Test
    void domainErrorsHaveTypeTitleStatusDetailAndTraceId() {
        UUID alice = newFundedWallet("EUR", "5.00");
        UUID bob = newWallet("EUR");

        var response = transfer(alice, bob, "20.00", "EUR");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.header("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("application/problem+json"));
        assertThat(response.body())
                .containsEntry("type", "INSUFFICIENT_FUNDS")
                .containsEntry("title", "Insufficient funds")
                .containsEntry("status", 422)
                .containsEntry("detail", "Account " + alice + " has insufficient available funds")
                .containsKey("traceId");
        assertNoInternalsLeaked(response);
    }

    @Test
    void traceIdInBodyMatchesResponseHeader() {
        var response = api.get("/api/v1/accounts/" + UUID.randomUUID());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(response.string("title")).isEqualTo("Account not found");
        assertThat(response.string("traceId")).isNotBlank();
        assertThat(response.header("X-Trace-Id")).contains(response.string("traceId"));
    }

    @Test
    void wellFormedIncomingTraceIdIsPropagated() {
        String traceId = "client-" + UUID.randomUUID();

        var response = api.get("/api/v1/accounts/" + UUID.randomUUID(), Map.of("X-Trace-Id", traceId));

        assertThat(response.string("traceId")).isEqualTo(traceId);
        assertThat(response.header("X-Trace-Id")).contains(traceId);
    }

    @Test
    void malformedIncomingTraceIdIsReplaced() {
        var response = api.get("/api/v1/accounts/" + UUID.randomUUID(), Map.of("X-Trace-Id", "<script>"));

        assertThat(response.string("traceId")).isNotEqualTo("<script>").matches("[0-9a-f]{32}");
    }

    @Test
    void frameworkErrorsUseTheSameShape() {
        var unknownRoute = api.get("/api/v1/nope");
        assertThat(unknownRoute.status()).isEqualTo(404);
        assertThat(unknownRoute.code()).isEqualTo("NOT_FOUND");
        assertThat(unknownRoute.body()).containsKeys("title", "status", "detail", "traceId");
        assertNoInternalsLeaked(unknownRoute);

        var badUuid = api.get("/api/v1/accounts/not-a-uuid");
        assertThat(badUuid.status()).isEqualTo(400);
        assertThat(badUuid.code()).isEqualTo("INVALID_PARAMETER");
        assertNoInternalsLeaked(badUuid);

        var malformed = api.post("/api/v1/transfers", "{not json");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.code()).isEqualTo("MALFORMED_REQUEST");
        assertNoInternalsLeaked(malformed);
    }

    @Test
    void namedDomainExceptionsSurfaceAsDistinctTypes() {
        UUID frozen = newFundedWallet("EUR", "10.00");
        UUID other = newWallet("EUR");
        api.post("/api/v1/accounts/" + frozen + "/freeze", null);
        var frozenTransfer = transfer(frozen, other, "1.00", "EUR");
        assertThat(frozenTransfer.status()).isEqualTo(422);
        assertThat(frozenTransfer.code()).isEqualTo("ACCOUNT_FROZEN");
        assertThat(frozenTransfer.string("title")).isEqualTo("Account frozen");

        UUID payer = newFundedWallet("EUR", "50.00");
        UUID merchant = newWallet("EUR");
        UUID payment = UUID.fromString(api.post("/api/v1/payments", Map.of(
                "payerAccountId", payer, "merchantAccountId", merchant,
                "amount", "10.00", "currency", "EUR")).string("id"));
        api.post("/api/v1/payments/" + payment + "/authorize", null);
        api.post("/api/v1/payments/" + payment + "/capture", null);

        var secondCapture = api.post("/api/v1/payments/" + payment + "/capture", null);
        assertThat(secondCapture.status()).isEqualTo(409);
        assertThat(secondCapture.code()).isEqualTo("PAYMENT_ALREADY_CAPTURED");

        var authorizeCaptured = api.post("/api/v1/payments/" + payment + "/authorize", null);
        assertThat(authorizeCaptured.status()).isEqualTo(409);
        assertThat(authorizeCaptured.code()).isEqualTo("INVALID_PAYMENT_STATE");
    }

    private static void assertNoInternalsLeaked(ApiClient.Response response) {
        assertThat(response.body()).doesNotContainKeys("trace", "stackTrace", "exception", "message", "code");
        assertThat(response.body().toString()).doesNotContain("at com.", "at org.", "Exception");
    }
}
