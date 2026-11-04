package com.kgiannopoulou.ledgerforge.risk.api;

import com.kgiannopoulou.ledgerforge.risk.application.PaymentScreening;
import com.kgiannopoulou.ledgerforge.risk.application.RiskAssessmentService;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskAlert;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskOutcome;
import com.kgiannopoulou.ledgerforge.risk.domain.StoredRiskDecision;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Risk data for operators (SUPPORT, AUDITOR, ADMIN; enforced in the security config). */
@RestController
@Validated
class RiskAlertController {

    private final RiskAssessmentService riskAssessment;
    private final PaymentScreening screening;

    RiskAlertController(RiskAssessmentService riskAssessment, PaymentScreening screening) {
        this.riskAssessment = riskAssessment;
        this.screening = screening;
    }

    /** {@code outcome=REVIEW} is the review queue. */
    @GetMapping("/api/v1/risk/decisions")
    List<StoredRiskDecision> decisions(@RequestParam(required = false) @Nullable RiskOutcome outcome,
                                       @RequestParam(required = false) @Nullable UUID accountId,
                                       @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return screening.recent(outcome, accountId, limit);
    }

    @GetMapping("/api/v1/risk/alerts")
    List<RiskAlert> alerts(@RequestParam(required = false) @Nullable UUID accountId,
                           @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return riskAssessment.recentAlerts(accountId, limit);
    }
}
