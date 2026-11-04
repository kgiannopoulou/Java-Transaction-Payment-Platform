package com.kgiannopoulou.ledgerforge.webhook.application;

import com.kgiannopoulou.ledgerforge.messaging.application.ProcessedEventLog;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEvent;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDeliveryRepository;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpoint;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Kafka side of webhooks: turns an event into one PENDING delivery per subscribed endpoint.
 *
 * <p>There is deliberately no HTTP here. A merchant's outage lasts minutes or hours, and retrying
 * it inside the consumer would stall the partition for every other customer. The consumer only
 * does fast local work, and {@link WebhookDispatcher} owns the slow, failure-prone part.
 */
@Service
public class WebhookFanOutService {

    public static final String CONSUMER = "webhooks";

    private final ProcessedEventLog processedEvents;
    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public WebhookFanOutService(ProcessedEventLog processedEvents, WebhookEndpointRepository endpoints,
                                WebhookDeliveryRepository deliveries, JsonMapper jsonMapper, Clock clock) {
        this.processedEvents = processedEvents;
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional
    public void handle(IntegrationEvent event) {
        if (!processedEvents.firstDelivery(CONSUMER, event.eventId())) {
            return;
        }
        WebhookEvent.Routed routed = WebhookEvent.from(event);
        List<WebhookEndpoint> subscribers = endpoints.findActiveSubscribers(routed.accountIds(), routed.type());
        if (subscribers.isEmpty()) {
            return;
        }
        String payload = jsonMapper.writeValueAsString(routed.event());
        Instant now = clock.instant();
        for (WebhookEndpoint endpoint : subscribers) {
            deliveries.enqueue(endpoint.id(), event.eventId(), routed.type().wireName(), payload, now);
        }
    }
}
