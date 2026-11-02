package com.kgiannopoulou.ledgerforge.payment.domain;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;
import com.kgiannopoulou.ledgerforge.shared.money.CurrencyMismatchException;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.UUID;

import static com.kgiannopoulou.ledgerforge.payment.domain.PaymentStatus.AUTHORIZED;
import static com.kgiannopoulou.ledgerforge.payment.domain.PaymentStatus.CANCELLED;
import static com.kgiannopoulou.ledgerforge.payment.domain.PaymentStatus.CAPTURED;
import static com.kgiannopoulou.ledgerforge.payment.domain.PaymentStatus.CREATED;
import static com.kgiannopoulou.ledgerforge.payment.domain.PaymentStatus.PARTIALLY_REFUNDED;
import static com.kgiannopoulou.ledgerforge.payment.domain.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    /** The complete transition table: every (from, to) pair, allowed or not. */
    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "CREATED,AUTHORIZED,true", "CREATED,CANCELLED,true", "CREATED,CAPTURED,false",
            "CREATED,PARTIALLY_REFUNDED,false", "CREATED,REFUNDED,false", "CREATED,CREATED,false",
            "AUTHORIZED,CAPTURED,true", "AUTHORIZED,CANCELLED,true", "AUTHORIZED,AUTHORIZED,false",
            "AUTHORIZED,REFUNDED,false", "AUTHORIZED,PARTIALLY_REFUNDED,false", "AUTHORIZED,CREATED,false",
            "CAPTURED,PARTIALLY_REFUNDED,true", "CAPTURED,REFUNDED,true", "CAPTURED,CANCELLED,false",
            "CAPTURED,CAPTURED,false", "CAPTURED,AUTHORIZED,false", "CAPTURED,CREATED,false",
            "PARTIALLY_REFUNDED,PARTIALLY_REFUNDED,true", "PARTIALLY_REFUNDED,REFUNDED,true",
            "PARTIALLY_REFUNDED,CAPTURED,false", "PARTIALLY_REFUNDED,CANCELLED,false",
            "REFUNDED,CAPTURED,false", "REFUNDED,PARTIALLY_REFUNDED,false", "REFUNDED,CANCELLED,false",
            "REFUNDED,REFUNDED,false", "CANCELLED,AUTHORIZED,false", "CANCELLED,CAPTURED,false",
            "CANCELLED,CANCELLED,false"
    })
    void transitionTable(PaymentStatus from, PaymentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void terminalStates() {
        assertThat(REFUNDED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(CAPTURED.isTerminal()).isFalse();
    }

    @Test
    void happyPathWithPartialThenFullRefund() {
        Payment payment = newPayment("100.00");
        payment.markAuthorized(NOW);
        payment.markCaptured(NOW);

        payment.recordRefund(eur("30.00"), NOW);
        assertThat(payment.getStatus()).isEqualTo(PARTIALLY_REFUNDED);
        assertThat(payment.getRefundableAmount()).isEqualTo(eur("70.00"));

        payment.recordRefund(eur("70.00"), NOW);
        assertThat(payment.getStatus()).isEqualTo(REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualTo(eur("100.00"));
    }

    @Test
    void capturingARefundedPaymentFails() {
        Payment payment = newPayment("50.00");
        payment.markAuthorized(NOW);
        payment.markCaptured(NOW);
        payment.recordRefund(eur("50.00"), NOW);

        assertThatThrownBy(() -> payment.markCaptured(NOW))
                .isInstanceOf(InvalidPaymentTransitionException.class)
                .hasMessageContaining("REFUNDED to CAPTURED");
    }

    @Test
    void cannotRefundMoreThanCaptured() {
        Payment payment = newPayment("50.00");
        payment.markAuthorized(NOW);
        payment.markCaptured(NOW);
        payment.recordRefund(eur("20.00"), NOW);

        assertThatThrownBy(() -> payment.recordRefund(eur("30.01"), NOW))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("exceeds refundable amount EUR 30.00");
    }

    @Test
    void cannotRefundBeforeCapture() {
        Payment payment = newPayment("50.00");
        payment.markAuthorized(NOW);
        assertThatThrownBy(() -> payment.recordRefund(eur("10.00"), NOW))
                .isInstanceOf(InvalidPaymentTransitionException.class);
    }

    @Test
    void refundCurrencyMustMatch() {
        Payment payment = newPayment("50.00");
        payment.markAuthorized(NOW);
        payment.markCaptured(NOW);
        assertThatThrownBy(() -> payment.recordRefund(Money.of("10", "USD"), NOW))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void cancelOnlyBeforeCapture() {
        Payment created = newPayment("10.00");
        created.markCancelled(NOW);
        assertThat(created.getStatus()).isEqualTo(CANCELLED);

        Payment captured = newPayment("10.00");
        captured.markAuthorized(NOW);
        captured.markCaptured(NOW);
        assertThatThrownBy(() -> captured.markCancelled(NOW)).isInstanceOf(InvalidPaymentTransitionException.class);
    }

    @Test
    void creationRules() {
        UUID same = UUID.randomUUID();
        assertThatThrownBy(() -> Payment.create(same, same, eur("10"), null, NOW))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(newPayment("1.00").getStatus()).isEqualTo(CREATED);
        assertThat(AUTHORIZED.allowedTransitions()).containsExactlyInAnyOrder(CAPTURED, CANCELLED);
    }

    @Test
    void sealedCommandsAreExhaustive() {
        PaymentCommand command = new PaymentCommand.RefundPayment(UUID.randomUUID(), eur("5"));
        String label = switch (command) {
            case PaymentCommand.AuthorizePayment a -> "authorize";
            case PaymentCommand.CapturePayment c -> "capture";
            case PaymentCommand.CancelPayment c -> "cancel";
            case PaymentCommand.RefundPayment r -> "refund " + r.amount();
        };
        assertThat(label).isEqualTo("refund EUR 5.00");
    }

    private static Payment newPayment(String amount) {
        return Payment.create(UUID.randomUUID(), UUID.randomUUID(), eur(amount), "test", NOW);
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }
}
