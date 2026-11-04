package com.kgiannopoulou.ledgerforge.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the actual log events, with their context, at DEBUG: the level most likely to leak. Rules
 * that exist only as a convention get broken by the first convenient {@code log.debug(request)}.
 */
class StructuredLoggingTest extends IntegrationTest {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final Logger application = (Logger) LoggerFactory.getLogger("com.kgiannopoulou.ledgerforge");
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = application.getLevel();
        application.setLevel(Level.DEBUG);
        events.start();
        root.addAppender(events);
    }

    @AfterEach
    void stopCapturing() {
        root.detachAppender(events);
        application.setLevel(previousLevel);
    }

    // ---- context on every line ---------------------------------------------------------------

    @Test
    void paymentLogsCarryTraceCustomerAndPaymentIds() {
        UUID payer = newWallet("EUR");
        UUID merchant = newWallet("EUR");

        UUID payment = newPayment(payer, merchant, "1.00", "EUR");

        Map<String, String> context = contextOf("Payment created");
        assertThat(context).containsEntry("paymentId", payment.toString())
                .containsEntry("customerId", ownerOf(payer).toString());
        assertThat(context.get("traceId")).isNotBlank();
    }

    @Test
    void theTraceIdInAnErrorResponseFindsTheLogLine() {
        UUID payer = newWallet("EUR");
        UUID merchant = newWallet("EUR");
        UUID payment = newPayment(payer, merchant, "1.00", "EUR");

        var response = asOwnerOf(payer).post("/api/v1/payments/" + payment + "/authorize", null);

        assertThat(response.status()).isEqualTo(422);
        assertThat(contextOf("Request rejected: INSUFFICIENT_FUNDS"))
                .containsEntry("traceId", response.string("traceId"))
                .containsEntry("paymentId", payment.toString());
    }

    @Test
    void transferLogsCarryTheTransferId() {
        UUID alice = newFundedWallet("EUR", "10.00");
        UUID bob = newWallet("EUR");

        String transfer = transfer(alice, bob, "1.00", "EUR").string("id");

        assertThat(contextOf("Transfer completed")).containsEntry("transferId", transfer)
                .containsEntry("customerId", ownerOf(alice).toString());
    }

    @Test
    void contextEndsWithTheRequest() {
        UUID payer = newWallet("EUR");
        newPayment(payer, newWallet("EUR"), "1.00", "EUR");

        api.get("/api/v1/accounts/" + UUID.randomUUID()); // a later request with no payment or customer

        assertThat(contextOf("ACCOUNT_NOT_FOUND")).doesNotContainKeys("paymentId", "customerId");
    }

    // ---- what must never appear --------------------------------------------------------------

    @Test
    void tokensAreNeverLogged() {
        UUID customer = newCustomer();
        String token = tokens.forCustomer(customer);
        UUID wallet = newWallet(customer, "EUR");

        anonymous.withToken(token).get("/api/v1/accounts/" + wallet);
        anonymous.withToken("forged.token.value").get("/api/v1/accounts/" + wallet);

        assertThat(everythingLogged()).doesNotContain(token).doesNotContain("forged.token.value").doesNotContain("Bearer ");
    }

    @Test
    void webhookSecretsAreNeverLogged() {
        UUID wallet = newWallet("EUR");

        var created = asOwnerOf(wallet).post("/api/v1/webhook-endpoints", Map.of(
                "accountId", wallet, "url", "https://merchant.example.com/hooks",
                "eventTypes", List.of("payment.captured")));

        assertThat(created.status()).isEqualTo(201);
        assertThat(everythingLogged()).doesNotContain(created.string("secret")).doesNotContain("whsec_");
    }

    @Test
    void customerDetailsAreNeverLogged() {
        String email = "private-" + UUID.randomUUID() + "@example.com";

        assertThat(api.post("/api/v1/customers", Map.of("email", email, "fullName", "Private Person")).status())
                .isEqualTo(201);
        assertThat(api.post("/api/v1/customers", Map.of("email", email, "fullName", "Private Person")).status())
                .as("rejected duplicate").isEqualTo(409);

        assertThat(everythingLogged()).doesNotContain(email).doesNotContain("Private Person");
    }

    @Test
    void amountsAreNotPartOfRoutineLogs() {
        UUID alice = newFundedWallet("EUR", "10.00");
        UUID bob = newWallet("EUR");

        transfer(alice, bob, "7.13", "EUR");

        assertThat(everythingLogged()).doesNotContain("7.13");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Map<String, String> contextOf(String message) {
        return List.copyOf(events.list).reversed().stream()
                .filter(event -> event.getFormattedMessage().contains(message))
                .findFirst()
                .map(ILoggingEvent::getMDCPropertyMap)
                .orElseThrow(() -> new AssertionError("Nothing logged containing: " + message));
    }

    /** Messages, context and exceptions: everything an appender could write. */
    private String everythingLogged() {
        return List.copyOf(events.list).stream()
                .map(event -> event.getFormattedMessage() + " " + event.getMDCPropertyMap()
                        + (event.getThrowableProxy() == null ? "" : " " + event.getThrowableProxy().getMessage()))
                .collect(Collectors.joining("\n"));
    }
}
