package com.kgiannopoulou.ledgerforge.webhook.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signatures, in the widely used timestamped form:
 *
 * <pre>
 *   LedgerForge-Signature: t=1793793600,v1=5257a869e7...
 *   v1 = hex(HMAC_SHA256(secret, t + "." + rawBody))
 * </pre>
 *
 * Signing the timestamp stops a captured request from being replayed later: the receiver rejects
 * anything older than its tolerance, and the timestamp cannot be changed without the secret.
 * {@link #verify} is the reference implementation of what a receiver does.
 */
public final class WebhookSignature {

    public static final String HEADER = "LedgerForge-Signature";

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + hmacHex(secret, timestampSeconds, body);
    }

    public static boolean verify(String secret, String header, String body, Instant now, Duration tolerance) {
        Long timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            if (part.startsWith("t=")) {
                timestamp = parseLong(part.substring(2));
            } else if (part.startsWith("v1=")) {
                signature = part.substring(3);
            }
        }
        if (timestamp == null || signature == null) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(timestamp), now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        // Constant-time comparison: a byte-by-byte early exit leaks the signature through timing.
        return MessageDigest.isEqual(hmacHex(secret, timestamp, body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
