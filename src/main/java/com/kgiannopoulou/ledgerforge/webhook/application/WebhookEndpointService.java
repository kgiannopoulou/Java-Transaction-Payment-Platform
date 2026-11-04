package com.kgiannopoulou.ledgerforge.webhook.application;

import com.kgiannopoulou.ledgerforge.audit.application.AuditTrail;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditAction;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditedEntity;
import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;
import com.kgiannopoulou.ledgerforge.shared.error.ConflictException;
import com.kgiannopoulou.ledgerforge.shared.error.NotFoundException;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookAttempt;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDelivery;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDeliveryRepository;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDeliveryStatus;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpoint;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpointRepository;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpointStatus;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEventType;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Endpoint registration, delivery history and manual replay. */
@Service
public class WebhookEndpointService {

    public record RegisterEndpoint(UUID accountId, String url, @Nullable String description,
                                   Set<WebhookEventType> eventTypes) {
    }

    /** The only time the plaintext secret leaves the service. */
    public record RegisteredEndpoint(WebhookEndpoint endpoint, String secret) {
    }

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookSecrets secrets;
    private final WebhookUrlPolicy urlPolicy;
    private final WebhookProperties properties;
    private final AuditTrail audit;
    private final Clock clock;

    public WebhookEndpointService(WebhookEndpointRepository endpoints, WebhookDeliveryRepository deliveries,
                                  WebhookSecrets secrets, WebhookUrlPolicy urlPolicy, WebhookProperties properties,
                                  AuditTrail audit, Clock clock) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.secrets = secrets;
        this.urlPolicy = urlPolicy;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public RegisteredEndpoint register(RegisterEndpoint command) {
        urlPolicy.validate(command.url());
        if (endpoints.countActive(command.accountId()) >= properties.maxEndpointsPerAccount()) {
            throw new BusinessRuleViolationException("WEBHOOK_ENDPOINT_LIMIT_REACHED",
                    "An account can have at most " + properties.maxEndpointsPerAccount() + " active webhook endpoints");
        }
        String secret = secrets.generate();
        var endpoint = new WebhookEndpoint(UUID.randomUUID(), command.accountId(), command.url(), command.description(),
                command.eventTypes(), WebhookEndpointStatus.ACTIVE, clock.instant());
        endpoints.save(endpoint, secrets.encrypt(secret));
        audit.record(AuditAction.WEBHOOK_ENDPOINT_REGISTERED, AuditedEntity.WEBHOOK_ENDPOINT, endpoint.id(), Map.of(
                "accountId", endpoint.accountId().toString(),
                "url", endpoint.url()));
        return new RegisteredEndpoint(endpoint, secret);
    }

    @Transactional(readOnly = true)
    public WebhookEndpoint get(UUID id) {
        return endpoints.findById(id).orElseThrow(() -> new NotFoundException("Webhook endpoint", id));
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> listForAccount(UUID accountId) {
        return endpoints.findByAccountId(accountId);
    }

    /** Stops new deliveries. Rows are kept: the history outlives the endpoint. */
    @Transactional
    public void disable(UUID id) {
        WebhookEndpoint endpoint = get(id);
        if (endpoints.disable(id, clock.instant())) {
            audit.record(AuditAction.WEBHOOK_ENDPOINT_DISABLED, AuditedEntity.WEBHOOK_ENDPOINT, id, Map.of(
                    "accountId", endpoint.accountId().toString()));
        }
    }

    @Transactional(readOnly = true)
    public List<WebhookDelivery> deliveries(UUID endpointId, @Nullable WebhookDeliveryStatus status, int limit) {
        get(endpointId);
        return deliveries.findByEndpointId(endpointId, status, limit);
    }

    @Transactional(readOnly = true)
    public WebhookDelivery delivery(UUID id) {
        return deliveries.findById(id).orElseThrow(() -> new NotFoundException("Webhook delivery", id));
    }

    @Transactional(readOnly = true)
    public List<WebhookAttempt> attempts(UUID deliveryId) {
        return deliveries.findAttempts(deliveryId);
    }

    /**
     * Manual replay from the dead-letter state, for use once the receiver is fixed. A replay is one
     * more attempt, not a whole new schedule: if it fails too, the delivery is dead again.
     */
    @Transactional
    public WebhookDelivery retry(UUID deliveryId) {
        WebhookDelivery delivery = delivery(deliveryId);
        if (!get(delivery.endpointId()).isActive()) {
            throw new ConflictException("WEBHOOK_ENDPOINT_DISABLED", "The endpoint of this delivery is disabled");
        }
        if (!deliveries.requeueDead(deliveryId, clock.instant())) {
            throw new ConflictException("WEBHOOK_DELIVERY_NOT_DEAD", "Only dead deliveries can be retried");
        }
        return delivery(deliveryId);
    }
}
