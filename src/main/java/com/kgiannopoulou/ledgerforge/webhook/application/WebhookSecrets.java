package com.kgiannopoulou.ledgerforge.webhook.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Signing secrets. A password can be stored as a hash, but an HMAC needs the secret itself on every
 * delivery, so it has to be recoverable. It is kept AES-256-GCM encrypted under a key that lives
 * outside the database: a stolen database dump alone cannot forge webhooks. GCM also authenticates,
 * so a tampered ciphertext fails to decrypt instead of producing a wrong key.
 */
@Component
public class WebhookSecrets {

    private static final String PREFIX = "whsec_";
    private static final int SECRET_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public WebhookSecrets(@Value("${ledgerforge.webhooks.encryption-key}") String encryptionKey) {
        this.key = new SecretKeySpec(sha256(encryptionKey), "AES");
    }

    public String generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String encrypt(String secret) {
        byte[] iv = new byte[IV_BYTES]; // never reused: fresh and random for every secret
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt webhook secret", e);
        }
    }

    public String decrypt(String stored) {
        byte[] bytes = Base64.getDecoder().decode(stored);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot decrypt webhook secret", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
