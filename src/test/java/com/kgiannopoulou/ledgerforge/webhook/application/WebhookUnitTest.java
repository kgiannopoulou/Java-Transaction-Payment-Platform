package com.kgiannopoulou.ledgerforge.webhook.application;

import com.kgiannopoulou.ledgerforge.webhook.domain.InvalidWebhookUrlException;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Everything about webhooks that needs neither a database nor a network. */
class WebhookUnitTest {

    private static final String SECRET = "whsec_test";
    private static final String BODY = "{\"id\":\"evt_1\"}";
    private static final long T = 1_793_793_600L;
    private static final Instant NOW = Instant.ofEpochSecond(T);
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    // ---- signatures --------------------------------------------------------------------------

    @Test
    void signatureMatchesAnIndependentlyComputedHmac() {
        // printf '%s' '1793793600.{"id":"evt_1"}' | openssl dgst -sha256 -hmac whsec_test
        assertThat(WebhookSignature.sign(SECRET, T, BODY))
                .isEqualTo("t=1793793600,v1=a3ff84d511341ce736d51f5cc8547fefdbf12883b79c430714d1aaf570de8b45");
    }

    @Test
    void validSignatureVerifies() {
        assertThat(WebhookSignature.verify(SECRET, WebhookSignature.sign(SECRET, T, BODY), BODY, NOW, TOLERANCE)).isTrue();
    }

    @Test
    void aTamperedBodyFails() {
        String signature = WebhookSignature.sign(SECRET, T, BODY);

        assertThat(WebhookSignature.verify(SECRET, signature, BODY + " ", NOW, TOLERANCE)).isFalse();
    }

    @Test
    void aChangedTimestampFailsBecauseItIsSigned() {
        String signature = WebhookSignature.sign(SECRET, T, BODY);
        String replayed = signature.replace("t=" + T, "t=" + (T + 3600));

        assertThat(WebhookSignature.verify(SECRET, replayed, BODY, NOW.plusSeconds(3600), TOLERANCE)).isFalse();
    }

    @Test
    void anOldCapturedRequestIsRejected() {
        String signature = WebhookSignature.sign(SECRET, T, BODY);

        assertThat(WebhookSignature.verify(SECRET, signature, BODY, NOW.plus(TOLERANCE).plusSeconds(1), TOLERANCE)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "garbage", "t=abc,v1=00", "t=1793793600", "v1=00"})
    void malformedHeadersFailCleanly(String header) {
        assertThat(WebhookSignature.verify(SECRET, header, BODY, NOW, TOLERANCE)).isFalse();
    }

    // ---- retry schedule ----------------------------------------------------------------------

    @Test
    void scheduleGivesTheWaitAfterEachFailureThenRunsOut() {
        var schedule = new RetrySchedule(List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2)));

        assertThat(schedule.delayAfter(1)).contains(Duration.ofSeconds(10));
        assertThat(schedule.delayAfter(2)).contains(Duration.ofSeconds(30));
        assertThat(schedule.delayAfter(3)).contains(Duration.ofMinutes(2));
        assertThat(schedule.delayAfter(4)).isEqualTo(Optional.empty());
        assertThat(schedule.maxAttempts()).isEqualTo(4);
    }

    // ---- SSRF --------------------------------------------------------------------------------

    private final WebhookUrlPolicy strict = new WebhookUrlPolicy(false);

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hook",          // loopback
            "https://[::1]/hook",
            "https://10.0.0.5/hook",           // private networks
            "https://172.16.0.1/hook",
            "https://192.168.1.1/hook",
            "https://169.254.169.254/latest",  // cloud metadata service
            "https://100.64.0.1/hook",         // carrier-grade NAT
            "https://0.0.0.0/hook",
            "https://[fc00::1]/hook",          // IPv6 unique local
            "https://[fe80::1]/hook",          // IPv6 link local
            "https://[::ffff:10.0.0.5]/hook",  // IPv4-mapped IPv6
            "https://224.0.0.1/hook"           // multicast
    })
    void internalAddressesAreRefused(String url) {
        assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(InvalidWebhookUrlException.class)
                .hasMessageContaining("public address");
    }

    @Test
    void plainHttpIsRefused() {
        assertThatThrownBy(() -> strict.validate("http://93.184.216.34/hook"))
                .isInstanceOf(InvalidWebhookUrlException.class).hasMessageContaining("https");
    }

    @Test
    void publicHttpsAddressIsAccepted() {
        assertThatCode(() -> strict.validate("https://93.184.216.34/hook")).doesNotThrowAnyException();
    }

    @Test
    void insecureModePermitsLocalReceiversButNotOtherSchemes() {
        var development = new WebhookUrlPolicy(true);

        assertThatCode(() -> development.validate("http://127.0.0.1:9000/hook")).doesNotThrowAnyException();
        assertThatThrownBy(() -> development.validate("file:///etc/passwd")).isInstanceOf(InvalidWebhookUrlException.class);
    }

    // ---- secrets at rest ---------------------------------------------------------------------

    private final WebhookSecrets secrets = new WebhookSecrets("test-key");

    @Test
    void encryptedSecretRoundTripsAndEachEncryptionDiffers() {
        String secret = secrets.generate();

        String first = secrets.encrypt(secret);
        String second = secrets.encrypt(secret);

        assertThat(first).isNotEqualTo(second).doesNotContain(secret);
        assertThat(secrets.decrypt(first)).isEqualTo(secret);
        assertThat(secrets.decrypt(second)).isEqualTo(secret);
    }

    @Test
    void aDifferentKeyCannotDecrypt() {
        String stored = secrets.encrypt("whsec_x");

        assertThatThrownBy(() -> new WebhookSecrets("other-key").decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertextIsDetected() {
        byte[] bytes = java.util.Base64.getDecoder().decode(secrets.encrypt("whsec_x"));
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> secrets.decrypt(java.util.Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class);
    }
}
