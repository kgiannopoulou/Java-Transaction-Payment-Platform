package com.kgiannopoulou.ledgerforge.idempotency.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 fingerprint of a canonical request, used to detect key reuse with a different body. */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String sha256(String scope, byte[] canonicalRequest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(scope.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(canonicalRequest);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
