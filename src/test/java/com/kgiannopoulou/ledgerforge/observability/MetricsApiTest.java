package com.kgiannopoulou.ledgerforge.observability;

import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The Prometheus endpoint exactly as a scraper sees it: real text format, real credentials. */
@AutoConfigureMetrics
@TestPropertySource(properties = "ledgerforge.security.metrics.password=scrape-secret")
class MetricsApiTest extends IntegrationTest {

    private static final String PROMETHEUS = "/actuator/prometheus";

    /**
     * Micrometer's payments.created. The Prometheus client reserves the "_created" suffix and
     * strips it; monitoring/prometheus/prometheus.yml stores it as payments_created_total.
     */
    private static final String PAYMENTS_CREATED = "payments_total";

    @LocalServerPort
    int port;

    // ---- the contract ------------------------------------------------------------------------

    @Test
    void theAgreedMetricsAreExported() {
        UUID payer = newFundedWallet("EUR", "100.00");
        UUID merchant = newWallet("EUR");
        transfer(payer, merchant, "1.00", "EUR");
        newPayment(payer, merchant, "1.00", "EUR");

        assertThat(scrape()).contains(
                PAYMENTS_CREATED,
                "transfers_total{",
                "transfer_duration_seconds_bucket{",
                "transfer_duration_seconds_count{",
                "outbox_pending_events",
                "http_server_requests_seconds_bucket{");
    }

    @Test
    void countersExistBeforeTheFirstEventSoDashboardsShowZeroNotNoData() {
        assertThat(scrape()).contains(PAYMENTS_CREATED, "webhook_failures_total");
    }

    @Test
    void createdPaymentsAreCountedOnceEvenWhenTheRequestIsReplayed() {
        UUID payer = newWallet("EUR");
        UUID merchant = newWallet("EUR");
        ApiClient owner = asOwnerOf(payer);
        Map<String, Object> body = Map.of("payerAccountId", payer, "merchantAccountId", merchant,
                "amount", "1.00", "currency", "EUR");
        double before = value(PAYMENTS_CREATED);

        assertThat(owner.post("/api/v1/payments", body, "metrics-1").status()).isEqualTo(201);
        assertThat(owner.post("/api/v1/payments", body, "metrics-1").status()).isEqualTo(201);

        assertThat(value(PAYMENTS_CREATED)).isEqualTo(before + 1);
    }

    @Test
    void failedPaymentsAreCountedByOperationAndReason() {
        UUID payer = newWallet("EUR"); // no funds
        UUID merchant = newWallet("EUR");
        UUID payment = newPayment(payer, merchant, "1.00", "EUR");
        String insufficient = "payments_failed_total{operation=\"authorize\",reason=\"INSUFFICIENT_FUNDS\"}";
        double before = value(insufficient);

        assertThat(asOwnerOf(payer).post("/api/v1/payments/" + payment + "/authorize", null).status()).isEqualTo(422);

        assertThat(value(insufficient)).isEqualTo(before + 1);
    }

    @Test
    void aBlockedPaymentIsAFailureNotACreation() {
        UUID payer = newWallet("EUR");
        api.post("/api/v1/accounts/" + payer + "/freeze", null);
        UUID merchant = newWallet("EUR");
        String blocked = "payments_failed_total{operation=\"create\",reason=\"PAYMENT_BLOCKED\"}";
        double failedBefore = value(blocked);
        double createdBefore = value(PAYMENTS_CREATED);

        asOwnerOf(payer).post("/api/v1/payments", Map.of("payerAccountId", payer, "merchantAccountId", merchant,
                "amount", "1.00", "currency", "EUR"));

        assertThat(value(blocked)).isEqualTo(failedBefore + 1);
        assertThat(value(PAYMENTS_CREATED)).isEqualTo(createdBefore);
    }

    @Test
    void transfersAreCountedByOutcome() {
        UUID poor = newWallet("EUR");
        UUID bob = newWallet("EUR");
        UUID rich = newFundedWallet("EUR", "10.00");
        double completed = value("transfers_total{outcome=\"completed\"}");
        double failed = value("transfers_total{outcome=\"failed\"}");

        transfer(rich, bob, "1.00", "EUR");
        transfer(poor, bob, "1.00", "EUR");

        assertThat(value("transfers_total{outcome=\"completed\"}")).isEqualTo(completed + 1);
        assertThat(value("transfers_total{outcome=\"failed\"}")).isEqualTo(failed + 1);
    }

    @Test
    void noIdEverBecomesALabel() {
        UUID payer = newFundedWallet("EUR", "10.00");
        UUID merchant = newWallet("EUR");
        UUID payment = newPayment(payer, merchant, "1.00", "EUR");

        assertThat(scrape()).doesNotContain(payer.toString()).doesNotContain(payment.toString());
    }

    // ---- access ------------------------------------------------------------------------------

    @Test
    void scrapingNeedsThePrometheusCredentials() {
        assertThat(get(PROMETHEUS, null).statusCode()).isEqualTo(401);
        assertThat(get(PROMETHEUS, basic("prometheus", "wrong")).statusCode()).isEqualTo(401);
        assertThat(get(PROMETHEUS, basic("prometheus", "scrape-secret")).statusCode()).isEqualTo(200);
    }

    @Test
    void theScrapeUserCanReachNothingElse() {
        String credentials = basic("prometheus", "scrape-secret");

        assertThat(get("/actuator/metrics", credentials).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/admin/reconciliation", credentials).statusCode()).isEqualTo(401);
    }

    @Test
    void anAdminTokenDoesNotOpenTheScrapeEndpoint() {
        assertThat(api.get(PROMETHEUS).status()).isEqualTo(401);
    }

    @Test
    void metricsDetailsStayAdminOnlyWhileHealthStaysPublic() {
        assertThat(anonymous.get("/actuator/health").status()).isEqualTo(200);
        assertThat(anonymous.get("/actuator/metrics").status()).isEqualTo(401);
        assertThat(api.get("/actuator/metrics/payments.created").status()).isIn(200, 404);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private String scrape() {
        HttpResponse<String> response = get(PROMETHEUS, basic("prometheus", "scrape-secret"));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    /** The sample's value, or 0 if the series does not exist yet. */
    private double value(String series) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(series) + " (\\S+)$").matcher(scrape());
        return matcher.find() ? new BigDecimal(matcher.group(1)).doubleValue() : 0;
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String authorization) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
