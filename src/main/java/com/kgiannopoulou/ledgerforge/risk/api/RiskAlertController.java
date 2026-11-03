package com.kgiannopoulou.ledgerforge.risk.api;

import com.kgiannopoulou.ledgerforge.risk.application.RiskAssessmentService;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskAlert;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Risk alerts for operators (SUPPORT, AUDITOR, ADMIN; enforced in the security config). */
@RestController
@Validated
class RiskAlertController {

    private final RiskAssessmentService riskAssessment;

    RiskAlertController(RiskAssessmentService riskAssessment) {
        this.riskAssessment = riskAssessment;
    }

    @GetMapping("/api/v1/risk/alerts")
    List<RiskAlert> alerts(@RequestParam(required = false) @Nullable UUID accountId,
                           @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return riskAssessment.recentAlerts(accountId, limit);
    }
}
