package com.kgiannopoulou.ledgerforge.support;

import com.kgiannopoulou.ledgerforge.ledger.application.LedgerReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for black-box tests: real HTTP server on a random port, real PostgreSQL.
 * Tests never clean the database (the ledger is append-only by design); instead every test
 * creates its own customers and wallets with unique emails.
 *
 * <p>Subclasses choose the database with a {@code @DynamicPropertySource}; most extend
 * {@link IntegrationTest}, which uses the shared container.
 */
// accept-count: the concurrency tests open hundreds of TCP connections at the same instant;
// Tomcat's default backlog of 100 makes the OS refuse some of them (notably on Windows).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.accept-count=1000")
public abstract class ApiTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    protected LedgerReconciliationService reconciliation;

    protected ApiClient api;

    @BeforeEach
    void createClient() {
        api = new ApiClient(port);
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
        return UUID.fromString(response.string("id"));
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

    protected ApiClient.Response transfer(UUID from, UUID to, String amount, String currency) {
        return transfer(from, to, amount, currency, null);
    }

    protected ApiClient.Response transfer(UUID from, UUID to, String amount, String currency, String key) {
        return api.post("/api/v1/transfers", Map.of(
                "sourceAccountId", from,
                "destinationAccountId", to,
                "amount", amount,
                "currency", currency), key);
    }

    protected void assertLedgerConsistentFor(Collection<UUID> accountIds) {
        assertThat(reconciliation.mismatchesFor(accountIds))
                .as("stored balances must equal ledger-derived balances")
                .isEmpty();
    }
}
