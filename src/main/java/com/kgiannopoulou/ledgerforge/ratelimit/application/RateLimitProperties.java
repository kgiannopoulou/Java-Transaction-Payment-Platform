package com.kgiannopoulou.ledgerforge.ratelimit.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** @param payments limit on {@code POST /payments}, per payer account */
@ConfigurationProperties("ledgerforge.rate-limit")
public record RateLimitProperties(@DefaultValue Policy payments) {

    public record Policy(@DefaultValue("100") int limit, @DefaultValue("1m") Duration window) {
    }
}
