package com.kgiannopoulou.ledgerforge.api;

import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The third layer of defence in depth. Every test here goes around Bean Validation and the domain
 * model with raw SQL, as a bug, a manual fix or a second writer would, and proves PostgreSQL
 * refuses the row by itself. See docs/database-design.md.
 */
class DatabaseConstraintsTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    // ---- amounts -----------------------------------------------------------------------------

    @ParameterizedTest(name = "payment amount {0} is rejected")
    @CsvSource({"0.0000", "-0.0100"})
    void paymentAmountMustBePositive(BigDecimal amount) {
        UUID payer = newWallet("EUR");
        UUID merchant = newWallet("EUR");

        assertRejected("check constraint", () -> insertPayment(payer, merchant, amount, "EUR", "CREATED"));
    }

    @Test
    void transferAmountMustBePositive() {
        UUID source = newWallet("EUR");
        UUID destination = newWallet("EUR");

        assertRejected("transfers_amount_check", () -> insertTransfer(source, destination, BigDecimal.ZERO, "EUR"));
    }

    @Test
    void customerWalletCannotGoNegative() {
        UUID wallet = newWallet("EUR");

        assertRejected("ck_accounts_non_negative",
                () -> jdbc.sql("UPDATE accounts SET balance = -0.01 WHERE id = ?").param(wallet).update());
    }

    // ---- cross-table rules, as declarative composite foreign keys ------------------------------

    @Test
    void paymentCurrencyMustMatchBothAccounts() {
        UUID eurPayer = newWallet("EUR");
        UUID eurMerchant = newWallet("EUR");
        UUID usdMerchant = newWallet("USD");

        assertRejected("fk_payments_payer_currency",
                () -> insertPayment(eurPayer, eurMerchant, BigDecimal.TEN, "USD", "CREATED"));
        assertRejected("fk_payments_merchant_currency",
                () -> insertPayment(eurPayer, usdMerchant, BigDecimal.TEN, "EUR", "CREATED"));
    }

    @Test
    void transferCurrencyMustMatchBothAccounts() {
        UUID eur = newWallet("EUR");
        UUID usd = newWallet("USD");

        assertRejected("fk_transfers_destination_currency", () -> insertTransfer(eur, usd, BigDecimal.TEN, "EUR"));
    }

    // ---- state consistency -------------------------------------------------------------------

    @ParameterizedTest(name = "{0} with refunded_amount {1} is rejected")
    @CsvSource({"REFUNDED,4.00", "PARTIALLY_REFUNDED,0.00", "PARTIALLY_REFUNDED,10.00", "CAPTURED,1.00", "CREATED,1.00"})
    void refundedAmountMustAgreeWithTheStatus(String status, BigDecimal refunded) {
        UUID payment = newPayment(newFundedWallet("EUR", "10.00"), newWallet("EUR"), "10.00", "EUR");

        assertRejected("ck_payments_refund_matches_status",
                () -> jdbc.sql("UPDATE payments SET status = ?, refunded_amount = ? WHERE id = ?")
                        .params(status, refunded, payment).update());
    }

    @Test
    void refundCannotExceedThePayment() {
        UUID payment = newPayment(newFundedWallet("EUR", "10.00"), newWallet("EUR"), "10.00", "EUR");

        assertRejected("ck_payments_refund_limit",
                () -> jdbc.sql("UPDATE payments SET status = 'REFUNDED', refunded_amount = 10.01 WHERE id = ?")
                        .param(payment).update());
    }

    @Test
    void closedAccountHoldsNoMoney() {
        UUID wallet = newFundedWallet("EUR", "1.00");

        assertRejected("ck_accounts_closed_is_empty",
                () -> jdbc.sql("UPDATE accounts SET status = 'CLOSED' WHERE id = ?").param(wallet).update());
    }

    @Test
    void unknownStatusIsRejected() {
        UUID payment = newPayment(newFundedWallet("EUR", "10.00"), newWallet("EUR"), "10.00", "EUR");

        assertRejected("payments_status_check",
                () -> jdbc.sql("UPDATE payments SET status = 'SHIPPED' WHERE id = ?").param(payment).update());
    }

    @Test
    void completedIdempotencyKeyMustHaveAResponseToReplay() {
        OffsetDateTime now = OffsetDateTime.now();

        assertRejected("ck_idempotency_completed_has_response", () -> jdbc.sql("""
                        INSERT INTO idempotency_keys (scope, idempotency_key, request_hash, status, created_at, expires_at)
                        VALUES ('test', ?, 'hash', 'COMPLETED', ?, ?)""")
                .params(UUID.randomUUID().toString(), now, now.plusHours(1)).update());
    }

    @Test
    void outboxAttemptsCannotBeNegative() {
        OffsetDateTime now = OffsetDateTime.now();

        assertRejected("ck_outbox_attempts_non_negative", () -> jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload,
                                                   created_at, attempts, next_attempt_at)
                        VALUES (?, 'PAYMENT', ?, 'PaymentCaptured', 't', '{}'::jsonb, ?, -1, ?)""")
                .params(UUID.randomUUID(), UUID.randomUUID(), now, now).update());
    }

    // ---- format and uniqueness ---------------------------------------------------------------

    @Test
    void customerNameCannotBeBlankAndEmailMustLookLikeOne() {
        assertRejected("ck_customers_full_name_not_blank", () -> insertCustomer(unique() + "@example.com", "   "));
        assertRejected("ck_customers_email_format", () -> insertCustomer("not-an-email", "Eve"));
    }

    @Test
    void emailIsUniqueIgnoringCase() {
        String email = unique() + "@example.com";
        insertCustomer(email, "First");

        assertRejected("ux_customers_email", () -> insertCustomer(email.toUpperCase(), "Second"));
    }

    @Test
    void oneWalletPerCustomerPerCurrency() {
        UUID customer = newCustomer();
        newWallet(customer, "EUR");

        assertRejected("ux_accounts_customer_currency", () -> jdbc.sql("""
                        INSERT INTO accounts (id, customer_id, type, currency, status, created_at)
                        VALUES (?, ?, 'CUSTOMER_WALLET', 'EUR', 'ACTIVE', now())""")
                .params(UUID.randomUUID(), customer).update());
    }

    // ---- schema-wide rules -------------------------------------------------------------------

    @Test
    void everyTableHasAPrimaryKey() {
        assertThat(jdbc.sql("""
                        SELECT t.table_name FROM information_schema.tables t
                        WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
                          AND t.table_name <> 'flyway_schema_history'
                          AND NOT EXISTS (SELECT 1 FROM information_schema.table_constraints c
                                          WHERE c.table_schema = 'public' AND c.table_name = t.table_name
                                            AND c.constraint_type = 'PRIMARY KEY')""")
                .query(String.class).list()).isEmpty();
    }

    @Test
    void everyMoneyColumnIsExactDecimal() {
        assertThat(jdbc.sql("""
                        SELECT table_name || '.' || column_name FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND column_name IN ('amount', 'refunded_amount', 'balance')
                          AND NOT (data_type = 'numeric' AND numeric_precision = 19 AND numeric_scale = 4)""")
                .query(String.class).list()).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static void assertRejected(String constraint, Runnable statement) {
        assertThatThrownBy(statement::run).rootCause().hasMessageContaining(constraint);
    }

    private void insertPayment(UUID payer, UUID merchant, BigDecimal amount, String currency, String status) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                        INSERT INTO payments (id, payer_account_id, merchant_account_id, amount, currency, status,
                                              created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), payer, merchant, amount, currency, status, now, now).update();
    }

    private void insertTransfer(UUID source, UUID destination, BigDecimal amount, String currency) {
        jdbc.sql("""
                        INSERT INTO transfers (id, source_account_id, destination_account_id, amount, currency,
                                               status, created_at)
                        VALUES (?, ?, ?, ?, ?, 'PENDING', ?)""")
                .params(UUID.randomUUID(), source, destination, amount, currency, OffsetDateTime.now()).update();
    }

    private void insertCustomer(String email, String name) {
        jdbc.sql("INSERT INTO customers (id, email, full_name, status, created_at) VALUES (?, ?, ?, 'ACTIVE', ?)")
                .params(UUID.randomUUID(), email, name, OffsetDateTime.now()).update();
    }

    private static String unique() {
        return "user-" + UUID.randomUUID();
    }
}
