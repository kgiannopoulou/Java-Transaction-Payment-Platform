package com.kgiannopoulou.ledgerforge.risk.application;

import com.kgiannopoulou.ledgerforge.risk.domain.PaymentContext;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskAssessment;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskDecision;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs every {@link RiskRule} Spring finds and keeps the harshest verdict. The engine knows nothing
 * about individual rules: they are polymorphic and injected, so adding one touches no existing code.
 *
 * <p>All rules always run, even after a BLOCK, because reviewers want every reason.
 */
@Component
public class RiskEngine {

    static final String RULE_ERROR = "RULE_ERROR";

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final List<RiskRule> rules;

    public RiskEngine(List<RiskRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public RiskAssessment evaluate(PaymentContext context) {
        return RiskAssessment.of(rules.stream().map(rule -> safely(rule, context)).toList());
    }

    /**
     * A rule that crashes must not decide the payment either way: an exception would refuse good
     * customers, and ignoring it would wave through what it was meant to catch. A person decides.
     */
    private static RiskDecision safely(RiskRule rule, PaymentContext context) {
        try {
            return rule.evaluate(context);
        } catch (RuntimeException e) {
            log.error("Risk rule {} failed", rule.getClass().getSimpleName(), e);
            return RiskDecision.review(RULE_ERROR, rule.getClass().getSimpleName() + " could not be evaluated");
        }
    }
}
