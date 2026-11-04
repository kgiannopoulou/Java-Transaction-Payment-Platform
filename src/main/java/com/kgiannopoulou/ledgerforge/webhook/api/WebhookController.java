package com.kgiannopoulou.ledgerforge.webhook.api;

import com.kgiannopoulou.ledgerforge.shared.error.InvalidRequestException;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookEndpointService;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookEndpointService.RegisterEndpoint;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookEvent;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookAttempt;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDelivery;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDeliveryStatus;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpoint;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpointStatus;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEventType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Registration is limited to the account's owner; staff can read, to help with support cases. */
@RestController
@Validated
class WebhookController {

    private final WebhookEndpointService webhooks;

    WebhookController(WebhookEndpointService webhooks) {
        this.webhooks = webhooks;
    }

    record RegisterEndpointRequest(
            @NotNull UUID accountId,
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 255) String description,
            @NotEmpty Set<String> eventTypes) {
    }

    /** {@code secret} is present only in the response to registration. */
    record EndpointResponse(UUID id, UUID accountId, String url, String description, List<String> eventTypes,
                            WebhookEndpointStatus status, Instant createdAt, @Nullable String secret) {

        static EndpointResponse from(WebhookEndpoint endpoint, @Nullable String secret) {
            return new EndpointResponse(endpoint.id(), endpoint.accountId(), endpoint.url(), endpoint.description(),
                    endpoint.eventTypes().stream().map(WebhookEventType::wireName).sorted().toList(),
                    endpoint.status(), endpoint.createdAt(), secret);
        }
    }

    record DeliveryResponse(UUID id, UUID endpointId, String eventId, String eventType, WebhookDeliveryStatus status,
                            int attemptCount, @Nullable Instant nextAttemptAt, @Nullable String lastError,
                            Instant createdAt, @Nullable Instant deliveredAt, @Nullable String payload,
                            @Nullable List<WebhookAttempt> attempts) {

        static DeliveryResponse from(WebhookDelivery d, @Nullable List<WebhookAttempt> attempts) {
            return new DeliveryResponse(d.id(), d.endpointId(), WebhookEvent.publicId(d.eventId()), d.eventType(),
                    d.status(), d.attempts(), d.status() == WebhookDeliveryStatus.PENDING ? d.nextAttemptAt() : null,
                    d.lastError(), d.createdAt(), d.deliveredAt(), attempts == null ? null : d.payload(), attempts);
        }
    }

    @PostMapping("/api/v1/webhook-endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.ownsAccount(#request.accountId())")
    EndpointResponse register(@Valid @RequestBody RegisterEndpointRequest request) {
        var registered = webhooks.register(new RegisterEndpoint(request.accountId(), request.url(),
                request.description(), parseEventTypes(request.eventTypes())));
        return EndpointResponse.from(registered.endpoint(), registered.secret());
    }

    @GetMapping("/api/v1/webhook-endpoints")
    @PreAuthorize("@access.canReadAccount(#accountId)")
    List<EndpointResponse> list(@RequestParam UUID accountId) {
        return webhooks.listForAccount(accountId).stream().map(e -> EndpointResponse.from(e, null)).toList();
    }

    @GetMapping("/api/v1/webhook-endpoints/{id}")
    @PreAuthorize("@access.canReadWebhookEndpoint(#id)")
    EndpointResponse get(@PathVariable UUID id) {
        return EndpointResponse.from(webhooks.get(id), null);
    }

    @DeleteMapping("/api/v1/webhook-endpoints/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.ownsWebhookEndpoint(#id)")
    void disable(@PathVariable UUID id) {
        webhooks.disable(id);
    }

    @GetMapping("/api/v1/webhook-endpoints/{id}/deliveries")
    @PreAuthorize("@access.canReadWebhookEndpoint(#id)")
    List<DeliveryResponse> deliveries(@PathVariable UUID id,
                                      @RequestParam(required = false) @Nullable WebhookDeliveryStatus status,
                                      @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return webhooks.deliveries(id, status, limit).stream().map(d -> DeliveryResponse.from(d, null)).toList();
    }

    /** The delivery with its full attempt history. */
    @GetMapping("/api/v1/webhook-deliveries/{id}")
    @PreAuthorize("@access.canReadWebhookDelivery(#id)")
    DeliveryResponse delivery(@PathVariable UUID id) {
        return DeliveryResponse.from(webhooks.delivery(id), webhooks.attempts(id));
    }

    @PostMapping("/api/v1/webhook-deliveries/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("@access.ownsWebhookDelivery(#id)")
    DeliveryResponse retry(@PathVariable UUID id) {
        return DeliveryResponse.from(webhooks.retry(id), null);
    }

    private static Set<WebhookEventType> parseEventTypes(Set<String> wireNames) {
        Set<WebhookEventType> types = EnumSet.noneOf(WebhookEventType.class);
        for (String wireName : wireNames) {
            types.add(WebhookEventType.fromWireName(wireName).orElseThrow(() ->
                    new InvalidRequestException("UNKNOWN_WEBHOOK_EVENT_TYPE", "Unknown event type: " + wireName)));
        }
        return types;
    }
}
