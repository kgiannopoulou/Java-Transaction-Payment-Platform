package com.kgiannopoulou.ledgerforge.risk.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/** @param largeAmountThreshold transfers and captures at or above this amount raise an alert */
@ConfigurationProperties("ledgerforge.risk")
public record RiskProperties(@DefaultValue("10000.00") BigDecimal largeAmountThreshold) {
}
