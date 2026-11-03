package com.kgiannopoulou.ledgerforge.security.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HS256 settings. In production the secret comes from a secret store
 * ({@code LEDGERFORGE_JWT_SECRET}), or the resource server is pointed at an identity provider's
 * JWKS instead; the authorization rules do not change either way.
 */
@ConfigurationProperties("ledgerforge.security.jwt")
public record JwtProperties(
        @DefaultValue("ledgerforge") String issuer,
        String secret,
        @DefaultValue("1h") Duration ttl) {

    private static final int MIN_SECRET_BYTES = 32; // 256 bits for HS256

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "ledgerforge.security.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
    }
}
