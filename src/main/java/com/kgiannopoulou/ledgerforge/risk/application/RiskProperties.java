package com.kgiannopoulou.ledgerforge.risk.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * @param largeAmountThreshold transfers and captures at or above this raise an after-the-fact alert
 * @param rules                thresholds of the synchronous rules, checked before a payment exists
 */
@ConfigurationProperties("ledgerforge.risk")
public record RiskProperties(
        @DefaultValue("10000.00") BigDecimal largeAmountThreshold,
        @DefaultValue Rules rules) {

    public record Rules(@DefaultValue Velocity velocity, @DefaultValue LargeTransaction largeTransaction,
                        @DefaultValue RepeatedFailure repeatedFailure) {
    }

    public record Velocity(@DefaultValue("20") int maxAttempts, @DefaultValue("30s") Duration window) {
    }

    public record LargeTransaction(@DefaultValue("7500.00") BigDecimal threshold,
                                   @DefaultValue("30d") Duration newAccountAge) {
    }

    public record RepeatedFailure(@DefaultValue("5") int maxFailures, @DefaultValue("10m") Duration window) {
    }
}
