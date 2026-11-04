package com.kgiannopoulou.ledgerforge.property;

import com.kgiannopoulou.ledgerforge.payment.domain.Payment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentStatus;
import com.kgiannopoulou.ledgerforge.shared.error.DomainException;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties of the domain model, with no database. jqwik generates the inputs, and when one fails
 * it shrinks it to the smallest example that still fails, so a broken invariant arrives as a
 * readable counterexample rather than a thousand-step trace.
 */
class DomainPropertyTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    // ---- money -------------------------------------------------------------------------------

    @Property
    void addingThenSubtractingReturnsTheOriginal(@ForAll("cents") long a, @ForAll("cents") long b) {
        assertThat(eur(a).add(eur(b)).subtract(eur(b))).isEqualTo(eur(a));
    }

    @Property
    void additionDoesNotDependOnOrder(@ForAll("cents") long a, @ForAll("cents") long b) {
        assertThat(eur(a).add(eur(b))).isEqualTo(eur(b).add(eur(a)));
    }

    /** The bug that binary floating point has: 0.1 + 0.2. Summing cents one way or another must agree. */
    @Property
    void summingManyAmountsIsExact(@ForAll @Size(max = 200) List<@IntRange(min = 1, max = 100_000) Integer> amounts) {
        Money total = Money.of("0", "EUR");
        long cents = 0;
        for (int amount : amounts) {
            total = total.add(eur(amount));
            cents += amount;
        }
        assertThat(total).isEqualTo(eur(cents));
    }

    // ---- the payment state machine -----------------------------------------------------------

    enum Step { HOLD, APPROVE, REJECT, AUTHORIZE, CAPTURE, CANCEL, REFUND_SOME, REFUND_ALL }

    /** Whatever order commands arrive in, a payment never ends up in an impossible state. */
    @Property(tries = 2000)
    void noCommandSequenceBreaksAPayment(@ForAll @IntRange(min = 1, max = 1_000_000) int cents,
                                         @ForAll @Size(max = 12) List<Step> steps,
                                         @ForAll @IntRange(min = 1, max = 1_000_000) int refundCents) {
        Payment payment = Payment.create(UUID.randomUUID(), UUID.randomUUID(), eur(cents), "property", NOW);

        for (Step step : steps) {
            PaymentStatus before = payment.getStatus();
            try {
                apply(step, payment, refundCents);
            } catch (DomainException refused) {
                assertThat(payment.getStatus()).as("a refused command changes nothing").isEqualTo(before);
            }
            assertThat(before.isTerminal() && payment.getStatus() != before).as("terminal states are final").isFalse();

            Money refunded = payment.getRefundedAmount();
            assertThat(refunded.isNegative()).isFalse();
            assertThat(refunded.isGreaterThan(payment.getAmount())).as("refunds never exceed the payment").isFalse();
            assertThat(payment.getRefundableAmount().add(refunded)).isEqualTo(payment.getAmount());
            assertThat(!refunded.isZero() && !payment.getStatus().hasBeenCaptured())
                    .as("only captured money can be refunded").isFalse();
            assertThat(payment.getStatus() == PaymentStatus.REFUNDED).isEqualTo(refunded.equals(payment.getAmount()));
        }
    }

    private static void apply(Step step, Payment payment, int refundCents) {
        switch (step) {
            case HOLD -> payment.holdForReview(NOW);
            case APPROVE -> payment.approveReview(NOW);
            case REJECT -> payment.rejectReview(NOW);
            case AUTHORIZE -> payment.markAuthorized(NOW);
            case CAPTURE -> payment.markCaptured(NOW);
            case CANCEL -> payment.markCancelled(NOW);
            case REFUND_SOME -> payment.recordRefund(eur(refundCents), NOW);
            case REFUND_ALL -> payment.recordRefund(payment.getRefundableAmount(), NOW);
        }
    }

    @Provide
    Arbitrary<Long> cents() {
        return Arbitraries.longs().between(0, 100_000_000_000L);
    }

    private static Money eur(long cents) {
        return Money.of(BigDecimal.valueOf(cents, 2).toPlainString(), "EUR");
    }
}
