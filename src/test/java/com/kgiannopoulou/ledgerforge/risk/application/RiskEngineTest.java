package com.kgiannopoulou.ledgerforge.risk.application;

import com.kgiannopoulou.ledgerforge.risk.application.rules.FrozenAccountRule;
import com.kgiannopoulou.ledgerforge.risk.application.rules.LargeTransactionRule;
import com.kgiannopoulou.ledgerforge.risk.application.rules.RepeatedFailureRule;
import com.kgiannopoulou.ledgerforge.risk.application.rules.VelocityRule;
import com.kgiannopoulou.ledgerforge.risk.domain.PaymentContext;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskAssessment;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskDecision;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskOutcome;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskRule;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The rule table, with no Spring, database or Redis: rules are pure functions of their context. */
class RiskEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    private static final RiskProperties PROPERTIES = new RiskProperties(new BigDecimal("10000.00"),
            new RiskProperties.Rules(
                    new RiskProperties.Velocity(20, Duration.ofSeconds(30)),
                    new RiskProperties.LargeTransaction(new BigDecimal("7500.00"), Duration.ofDays(30)),
                    new RiskProperties.RepeatedFailure(5, Duration.ofMinutes(10))));

    private final RiskEngine engine = new RiskEngine(List.of(new FrozenAccountRule(), new VelocityRule(PROPERTIES),
            new LargeTransactionRule(PROPERTIES), new RepeatedFailureRule(PROPERTIES)));

    // ---- the table ---------------------------------------------------------------------------

    @ParameterizedTest(name = "€{0}, account {1} days old, {2} attempts, {3} failures, frozen={4} -> {5}")
    @CsvSource({
            // amount, age, attempts, failures, frozen, outcome
            "5.00,     400, 1,  0, false, ALLOW",
            "7499.99,  1,   1,  0, false, ALLOW",
            "7500.00,  1,   1,  0, false, REVIEW",
            "7500.00,  29,  1,  0, false, REVIEW",
            "7500.00,  30,  1,  0, false, ALLOW",
            "50000.00, 400, 1,  0, false, ALLOW",
            "5.00,     400, 19, 0, false, ALLOW",
            "5.00,     400, 20, 0, false, BLOCK",
            "5.00,     400, 1,  4, false, ALLOW",
            "5.00,     400, 1,  5, false, REVIEW",
            "5.00,     400, 1,  0, true,  BLOCK",
    })
    void decisionTable(BigDecimal amount, int ageDays, int attempts, int failures, boolean frozen, RiskOutcome expected) {
        assertThat(engine.evaluate(context(amount, ageDays, attempts, failures, frozen, true)).outcome()).isEqualTo(expected);
    }

    @Test
    void anAllowedPaymentTriggersNothing() {
        assertThat(engine.evaluate(context(new BigDecimal("5.00"), 400, 1, 0, false, true)).triggered()).isEmpty();
    }

    // ---- combining verdicts ------------------------------------------------------------------

    @Test
    void theHarshestVerdictWinsAndEveryReasonIsKept() {
        RiskAssessment assessment = engine.evaluate(context(new BigDecimal("9000.00"), 1, 25, 6, true, true));

        assertThat(assessment.outcome()).isEqualTo(RiskOutcome.BLOCK);
        assertThat(assessment.triggered()).extracting(RiskDecision::rule).containsExactly(
                FrozenAccountRule.NAME, VelocityRule.NAME, LargeTransactionRule.NAME, RepeatedFailureRule.NAME);
    }

    @Test
    void reviewBeatsAllowRegardlessOfRuleOrder() {
        RiskRule allow = context -> RiskDecision.allow();
        RiskRule review = context -> RiskDecision.review("R", "reason");

        assertThat(new RiskEngine(List.of(review, allow)).evaluate(anyContext()).outcome()).isEqualTo(RiskOutcome.REVIEW);
        assertThat(new RiskEngine(List.of(allow, review)).evaluate(anyContext()).outcome()).isEqualTo(RiskOutcome.REVIEW);
    }

    // ---- polymorphism ------------------------------------------------------------------------

    @Test
    void aNewRuleNeedsNoChangeToTheEngine() {
        RiskRule weekendRule = context -> RiskDecision.block("NO_WEEKENDS", "closed");

        assertThat(new RiskEngine(List.of(weekendRule)).evaluate(anyContext()).outcome()).isEqualTo(RiskOutcome.BLOCK);
    }

    @Test
    void noRulesMeansAllow() {
        assertThat(new RiskEngine(List.of()).evaluate(anyContext()).outcome()).isEqualTo(RiskOutcome.ALLOW);
    }

    // ---- failure modes -----------------------------------------------------------------------

    @Test
    void aCrashingRuleSendsThePaymentToAPersonInsteadOfDecidingIt() {
        RiskRule broken = context -> {
            throw new IllegalStateException("bug");
        };

        RiskAssessment assessment = new RiskEngine(List.of(broken)).evaluate(anyContext());

        assertThat(assessment.outcome()).isEqualTo(RiskOutcome.REVIEW);
        assertThat(assessment.triggered()).extracting(RiskDecision::rule).containsExactly(RiskEngine.RULE_ERROR);
    }

    @Test
    void aCrashingRuleDoesNotHideABlock() {
        RiskRule broken = context -> {
            throw new IllegalStateException("bug");
        };

        assertThat(new RiskEngine(List.of(broken, new FrozenAccountRule()))
                .evaluate(context(BigDecimal.ONE, 400, 1, 0, true, true)).outcome()).isEqualTo(RiskOutcome.BLOCK);
    }

    @Test
    void withoutSignalsTheCountBasedRulesStandAsideAndTheOthersStillRun() {
        assertThat(engine.evaluate(context(new BigDecimal("5.00"), 400, 999, 999, false, false)).outcome())
                .isEqualTo(RiskOutcome.ALLOW);
        assertThat(engine.evaluate(context(new BigDecimal("7500.00"), 1, 0, 0, false, false)).outcome())
                .isEqualTo(RiskOutcome.REVIEW);
        assertThat(engine.evaluate(context(new BigDecimal("5.00"), 400, 0, 0, true, false)).outcome())
                .isEqualTo(RiskOutcome.BLOCK);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static PaymentContext anyContext() {
        return context(BigDecimal.ONE, 400, 1, 0, false, true);
    }

    private static PaymentContext context(BigDecimal amount, int ageDays, long attempts, long failures, boolean frozen,
                                          boolean signals) {
        return new PaymentContext(UUID.randomUUID(), UUID.randomUUID(), Money.of(amount.toPlainString(), "EUR"), frozen,
                NOW.minus(Duration.ofDays(ageDays)), NOW, attempts, failures, signals);
    }
}
