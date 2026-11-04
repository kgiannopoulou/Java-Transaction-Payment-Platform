package com.kgiannopoulou.ledgerforge.webhook.infrastructure;

import com.kgiannopoulou.ledgerforge.webhook.application.WebhookProperties;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookRequest;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookResponse;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookSender;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookSignature;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Blocking-style HTTP on the JDK client. Blocking is fine here: callers run on virtual threads,
 * where waiting on a socket releases the carrier thread.
 *
 * <ul>
 *   <li><b>Redirects are never followed</b>: a redirect could lead to an internal address after
 *       the URL check has passed.</li>
 *   <li><b>The response body is discarded unread</b>: only the status matters, and a hostile
 *       endpoint must not be able to fill memory.</li>
 *   <li><b>The deadline covers the whole exchange</b>. {@code HttpRequest.timeout} stops once
 *       headers arrive, so an endpoint trickling its body would otherwise hold the call open past
 *       the dispatcher's lease. Waiting on the future enforces a real end-to-end limit.</li>
 * </ul>
 */
@Component
class HttpWebhookSender implements WebhookSender {

    private final HttpClient client;
    private final Duration requestTimeout;

    HttpWebhookSender(WebhookProperties properties) {
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = properties.requestTimeout();
    }

    @Override
    public WebhookResponse send(WebhookRequest webhook) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(webhook.url()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "LedgerForge-Webhooks/1.0")
                    .header(WebhookSignature.HEADER, webhook.signature())
                    .header("LedgerForge-Event-Id", webhook.eventId())
                    .header("LedgerForge-Delivery-Id", webhook.deliveryId().toString())
                    .header("LedgerForge-Attempt", Integer.toString(webhook.attempt()))
                    .POST(HttpRequest.BodyPublishers.ofString(webhook.body()))
                    .build();
        } catch (IllegalArgumentException e) {
            return WebhookResponse.failed("Invalid URL: " + e.getMessage());
        }

        CompletableFuture<HttpResponse<Void>> call = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try {
            return WebhookResponse.status(call.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS).statusCode());
        } catch (TimeoutException e) {
            call.cancel(true);
            return timedOut();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return cause instanceof HttpTimeoutException
                    ? timedOut()
                    : WebhookResponse.failed(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return WebhookResponse.failed("Interrupted");
        }
    }

    private WebhookResponse timedOut() {
        return WebhookResponse.failed("Timed out after " + requestTimeout.toMillis() + " ms");
    }
}
