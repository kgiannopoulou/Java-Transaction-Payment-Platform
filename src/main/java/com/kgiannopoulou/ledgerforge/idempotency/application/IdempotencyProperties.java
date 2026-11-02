package com.kgiannopoulou.ledgerforge.idempotency.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param ttl how long a completed key is replayed before it may be reused for a new request
 */
@ConfigurationProperties("ledgerforge.idempotency")
public record IdempotencyProperties(@DefaultValue("24h") Duration ttl) {
}
