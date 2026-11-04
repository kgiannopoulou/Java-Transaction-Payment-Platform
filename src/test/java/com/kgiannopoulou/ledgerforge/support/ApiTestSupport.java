package com.kgiannopoulou.ledgerforge.support;

import com.kgiannopoulou.ledgerforge.ledger.application.LedgerReconciliationService;
import com.kgiannopoulou.ledgerforge.security.application.JwtTokenIssuer;
import com.kgiannopoulou.ledgerforge.security.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for black-box tests: real HTTP server on a random port, real PostgreSQL, real JWTs.
 * Tests never clean the database (the ledger is append-only by design); instead every test
 * creates its own customers and wallets with unique emails.
 *
 * <p>Identities: {@link #api} is an ADMIN (onboarding, deposits, freezing, reads). Money only
 * moves with the owning customer's token: {@link #asOwnerOf} / {@link #asCustomer}. Fixture
 * helpers pick the right identity automatically.
 *
 * <p>Kafka is not started here: the outbox relay and the consumers are switched off, and events
 * simply accumulate in the outbox table. {@link KafkaIntegrationTest} turns them on.
 *
 * <p>Subclasses choose the database with a {@code @DynamicPropertySource}; most extend
 * {@link IntegrationTest}, which uses the shared container.
 */
// accept-count: the concurrency tests open hundreds of TCP connections at the same instant;
// Tomcat's default backlog of 100 makes the OS refuse some of them (notably on Windows).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.tomcat.accept-count=1000",
        "ledgerforge.outbox.relay.enabled=false",
        "ledgerforge.kafka.consumers.enabled=false",
        "ledgerforge.webhooks.dispatcher.enabled=false",
        "ledgerforge.webhooks.allow-insecure-urls=true",
        "ledgerforge.webhooks.request-timeout=1s",
        "spring.kafka.admin.auto-create=false"
})
@Tag("integration")
public abstract class ApiTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    protected LedgerReconciliationService reconciliation;

    @Autowired
    protected JwtTokenIssuer tokens;

    /** Authenticated as ADMIN. */
    protected ApiClient api;

    /** No credentials at all. */
    protected ApiClient anonymous;

    private final Map<UUID, UUID> ownerByAccount = new ConcurrentHashMap<>();
    private final Map<UUID, ApiClient> clientByCustomer = new ConcurrentHashMap<>();

    @BeforeEach
    void createClients() {
        anonymous = new ApiClient(port);
        api = anonymous.withToken(tokens.forStaff("test-admin", Role.ADMIN));
    }

    // ---- identities --------------------------------------------------------------------------

    protected ApiClient asCustomer(UUID customerId) {
        return clientByCustomer.computeIfAbsent(customerId, id -> anonymous.withToken(tokens.forCustomer(id)));
    }

    /** The customer who owns the wallet (created through {@link #newWallet}). */
    protected ApiClient asOwnerOf(UUID accountId) {
        UUID owner = ownerByAccount.get(accountId);
        assertThat(owner).as("owner of account %s is unknown to this test", accountId).isNotNull();
        return asCustomer(owner);
    }

    protected ApiClient as(String username, Role... roles) {
        return anonymous.withToken(tokens.forStaff(username, roles));
    }

    protected UUID ownerOf(UUID accountId) {
        return ownerByAccount.get(accountId);
    }

    // ---- fixtures ----------------------------------------------------------------------------

    protected UUID newCustomer() {
        var response = api.post("/api/v1/customers", Map.of(
                "email", "user-" + UUID.randomUUID() + "@example.com",
                "fullName", "Test Customer"));
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        return UUID.fromString(response.string("id"));
    }

    protected UUID newWallet(String currency) {
        return newWallet(newCustomer(), currency);
    }

    protected UUID newWallet(UUID customerId, String currency) {
        var response = api.post("/api/v1/accounts", Map.of("customerId", customerId, "currency", currency));
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        UUID wallet = UUID.fromString(response.string("id"));
        ownerByAccount.put(wallet, customerId);
        return wallet;
    }

    protected UUID newFundedWallet(String currency, String amount) {
        UUID wallet = newWallet(currency);
        deposit(wallet, amount, currency);
        return wallet;
    }

    protected void deposit(UUID wallet, String amount, String currency) {
        var response = api.post("/api/v1/accounts/" + wallet + "/deposits",
                Map.of("amount", amount, "currency", currency));
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
    }

    protected BigDecimal balanceOf(UUID accountId) {
        var response = api.get("/api/v1/accounts/" + accountId);
        assertThat(response.status()).isEqualTo(200);
        return new BigDecimal(response.string("balance"));
    }

    /** Transfer initiated by the owner of {@code from}. */
    protected ApiClient.Response transfer(UUID from, UUID to, String amount, String currency) {
        return transfer(from, to, amount, currency, null);
    }

    protected ApiClient.Response transfer(UUID from, UUID to, String amount, String currency, String key) {
        return asOwnerOf(from).post("/api/v1/transfers", Map.of(
                "sourceAccountId", from,
                "destinationAccountId", to,
                "amount", amount,
                "currency", currency), key);
    }

    /** Creates a payment as the payer; returns its id. */
    protected UUID newPayment(UUID payer, UUID merchant, String amount, String currency) {
        var response = asOwnerOf(payer).post("/api/v1/payments", Map.of(
                "payerAccountId", payer, "merchantAccountId", merchant,
                "amount", amount, "currency", currency));
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        return UUID.fromString(response.string("id"));
    }

    protected void assertLedgerConsistentFor(Collection<UUID> accountIds) {
        assertThat(reconciliation.mismatchesFor(accountIds))
                .as("stored balances must equal ledger-derived balances")
                .isEmpty();
    }
}
