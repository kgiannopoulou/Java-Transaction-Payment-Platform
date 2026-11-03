package com.kgiannopoulou.ledgerforge.api;

import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerAccountApiTest extends IntegrationTest {

    @Test
    void registersCustomerAndOpensWalletsPerCurrency() {
        UUID customer = newCustomer();
        UUID eur = newWallet(customer, "EUR");
        UUID usd = newWallet(customer, "USD");

        var account = api.get("/api/v1/accounts/" + eur);
        assertThat(account.status()).isEqualTo(200);
        assertThat(account.body()).containsEntry("currency", "EUR")
                .containsEntry("status", "ACTIVE")
                .containsEntry("type", "CUSTOMER_WALLET")
                .containsEntry("balance", "0.00")
                .containsEntry("customerId", customer.toString());

        var list = api.get("/api/v1/customers/" + customer + "/accounts");
        assertThat((List<?>) list.body().get("items")).hasSize(2);
        assertThat(usd).isNotEqualTo(eur);
    }

    @Test
    void emailsAreUniqueCaseInsensitively() {
        String email = "Dup-" + UUID.randomUUID() + "@Example.com";
        assertThat(api.post("/api/v1/customers", Map.of("email", email, "fullName", "A")).status()).isEqualTo(201);
        var duplicate = api.post("/api/v1/customers", Map.of("email", email.toLowerCase(), "fullName", "B"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void validatesRequests() {
        var invalid = api.post("/api/v1/customers", Map.of("email", "not-an-email", "fullName", ""));
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("VALIDATION_FAILED");
        assertThat((List<?>) invalid.body().get("errors")).hasSize(2);

        var badCurrency = api.post("/api/v1/accounts", Map.of("customerId", UUID.randomUUID(), "currency", "XYZ"));
        assertThat(badCurrency.status()).isEqualTo(400);
    }

    @Test
    void oneWalletPerCurrencyAndSupportedCurrenciesOnly() {
        UUID customer = newCustomer();
        newWallet(customer, "EUR");
        var duplicate = api.post("/api/v1/accounts", Map.of("customerId", customer, "currency", "EUR"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("ACCOUNT_ALREADY_EXISTS");

        var unsupported = api.post("/api/v1/accounts", Map.of("customerId", customer, "currency", "JPY"));
        assertThat(unsupported.status()).isEqualTo(422);
        assertThat(unsupported.code()).isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void unknownIdsReturn404AndMalformedIds400() {
        assertThat(api.get("/api/v1/accounts/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/customers/" + UUID.randomUUID()).code()).isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(api.get("/api/v1/accounts/42").status()).isEqualTo(400);
        var noCustomer = api.post("/api/v1/accounts", Map.of("customerId", UUID.randomUUID(), "currency", "EUR"));
        assertThat(noCustomer.status()).isEqualTo(404);
    }

    @Test
    void depositsAppearInTransactionHistory() {
        UUID wallet = newFundedWallet("EUR", "120.50");
        deposit(wallet, "9.50", "EUR");

        assertThat(balanceOf(wallet)).isEqualByComparingTo("130.00");
        var history = api.get("/api/v1/accounts/" + wallet + "/transactions?size=10");
        assertThat(history.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) history.body().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.getFirst()).containsEntry("amount", "9.50")
                .containsEntry("direction", "CREDIT")
                .containsEntry("type", "DEPOSIT");
        assertThat(history.body()).containsEntry("totalItems", 2);
    }

    @Test
    void depositValidation() {
        UUID wallet = newWallet("EUR");
        var tooPrecise = api.post("/api/v1/accounts/" + wallet + "/deposits", Map.of("amount", "1.001", "currency", "EUR"));
        assertThat(tooPrecise.status()).isEqualTo(400);
        assertThat(tooPrecise.code()).isEqualTo("INVALID_AMOUNT");

        var negative = api.post("/api/v1/accounts/" + wallet + "/deposits", Map.of("amount", "-5", "currency", "EUR"));
        assertThat(negative.status()).isEqualTo(400);

        var wrongCurrency = api.post("/api/v1/accounts/" + wallet + "/deposits", Map.of("amount", "5", "currency", "USD"));
        assertThat(wrongCurrency.status()).isEqualTo(422);
        assertThat(wrongCurrency.code()).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void freezeUnfreezeAndClose() {
        UUID wallet = newFundedWallet("GBP", "5.00");
        assertThat(api.post("/api/v1/accounts/" + wallet + "/freeze", null).body()).containsEntry("status", "FROZEN");
        assertThat(api.post("/api/v1/accounts/" + wallet + "/deposits", Map.of("amount", "1", "currency", "GBP")).code())
                .isEqualTo("ACCOUNT_FROZEN");
        assertThat(api.post("/api/v1/accounts/" + wallet + "/unfreeze", null).body()).containsEntry("status", "ACTIVE");

        var close = api.post("/api/v1/accounts/" + wallet + "/close", null);
        assertThat(close.status()).isEqualTo(422);
        assertThat(close.code()).isEqualTo("ACCOUNT_BALANCE_NOT_ZERO");

        UUID empty = newWallet("GBP");
        assertThat(api.post("/api/v1/accounts/" + empty + "/close", null).body()).containsEntry("status", "CLOSED");
    }

    @Test
    void healthEndpointIsUp() {
        var health = api.get("/actuator/health");
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body()).containsEntry("status", "UP");
    }
}
