package com.kgiannopoulou.ledgerforge.webhook.application;

import com.kgiannopoulou.ledgerforge.webhook.domain.InvalidWebhookUrlException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * SSRF defence. A webhook URL makes the platform send HTTP requests to an address chosen by a
 * customer, so without this check {@code http://169.254.169.254/} would read the cloud metadata
 * service and {@code http://10.0.0.5/} would reach internal systems from inside the network.
 *
 * <p>Enforced at registration (fast feedback) and again before every attempt, because a hostname
 * that resolved to a public address yesterday can resolve to a private one today. Redirects are
 * never followed, for the same reason.
 *
 * <p>{@code allow-insecure-urls} permits plain HTTP and private addresses for local development
 * and tests. It must stay off everywhere else.
 */
@Component
public class WebhookUrlPolicy {

    private final boolean allowInsecure;

    public WebhookUrlPolicy(@Value("${ledgerforge.webhooks.allow-insecure-urls:false}") boolean allowInsecure) {
        this.allowInsecure = allowInsecure;
    }

    /** @throws InvalidWebhookUrlException if the platform must not call this URL */
    public void validate(String url) {
        URI uri = parse(url);
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("https".equals(scheme) || "http".equals(scheme))) {
            throw new InvalidWebhookUrlException("Webhook URL must be an absolute http(s) URL");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidWebhookUrlException("Webhook URL must not contain credentials");
        }
        if (allowInsecure) {
            return;
        }
        if (!"https".equals(scheme)) {
            throw new InvalidWebhookUrlException("Webhook URL must use https");
        }
        for (InetAddress address : resolve(uri.getHost())) {
            if (!isPublic(address)) {
                throw new InvalidWebhookUrlException("Webhook URL must resolve to a public address");
            }
        }
    }

    private static URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidWebhookUrlException("Webhook URL is malformed");
        }
    }

    private static InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new InvalidWebhookUrlException("Webhook URL host cannot be resolved");
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        if (bytes.length == 4) {
            boolean thisNetwork = first == 0;
            boolean carrierGradeNat = first == 100 && second >= 64 && second <= 127;
            boolean broadcast = first == 255;
            return !(thisNetwork || carrierGradeNat || broadcast);
        }
        boolean uniqueLocal = (first & 0xFE) == 0xFC; // fc00::/7
        return !uniqueLocal;
    }
}
