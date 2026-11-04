package com.kgiannopoulou.ledgerforge.risk.application;

import com.kgiannopoulou.ledgerforge.risk.domain.PaymentContext;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskAssessment;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskDecisionRepository;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskOutcome;
import com.kgiannopoulou.ledgerforge.risk.domain.ScreenedPayment;
import com.kgiannopoulou.ledgerforge.risk.domain.StoredRiskDecision;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The entry point for the payment module: gathers signals, runs the engine, stores the verdict. */
@Service
public class PaymentScreening {

    private final RiskEngine engine;
    private final RiskSignals signals;
    private final RiskDecisionRepository decisions;
    private final MeterRegistry meters;
    private final Clock clock;

    public PaymentScreening(RiskEngine engine, RiskSignals signals, RiskDecisionRepository decisions,
                            MeterRegistry meters, Clock clock) {
        this.engine = engine;
        this.signals = signals;
        this.decisions = decisions;
        this.meters = meters;
        this.clock = clock;
    }

    public ScreenedPayment screen(UUID payerAccountId, UUID merchantAccountId, Money amount, boolean payerFrozen,
                                  Instant payerOpenedAt) {
        RiskSignals.Snapshot snapshot = signals.recordAttempt(payerAccountId);
        RiskAssessment assessment = engine.evaluate(new PaymentContext(payerAccountId, merchantAccountId, amount,
                payerFrozen, payerOpenedAt, clock.instant(), snapshot.attempts(), snapshot.failures(),
                snapshot.available()));
        meters.counter("ledgerforge.risk.decisions", "outcome", assessment.outcome().name()).increment();
        return new ScreenedPayment(payerAccountId, merchantAccountId, amount, assessment, !snapshot.available());
    }

    /** ALLOW and REVIEW: stored in the same transaction as the payment they belong to. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID paymentId, ScreenedPayment screened) {
        store(paymentId, screened);
    }

    /**
     * BLOCK. There is no payment, and the request's transaction has already rolled back, so this
     * runs in its own transaction. Otherwise the rollback would erase the record of the refusal.
     */
    @Transactional
    public void recordBlocked(ScreenedPayment screened) {
        store(null, screened);
        signals.recordFailure(screened.payerAccountId());
    }

    @Transactional(readOnly = true)
    public List<StoredRiskDecision> recent(@Nullable RiskOutcome outcome, @Nullable UUID payerAccountId, int limit) {
        return decisions.findRecent(outcome, payerAccountId, limit);
    }

    private void store(@Nullable UUID paymentId, ScreenedPayment screened) {
        decisions.save(new StoredRiskDecision(UUID.randomUUID(), paymentId, screened.payerAccountId(),
                screened.merchantAccountId(), screened.amount().amount(), screened.amount().currency(),
                screened.outcome(), screened.assessment().triggered(), screened.degraded(), clock.instant()));
    }
}
