package com.kgiannopoulou.ledgerforge.webhook.application;

import com.kgiannopoulou.ledgerforge.messaging.application.ProcessedEventLog;
import com.kgiannopoulou.ledgerforge.shared.event.AccountFrozenEvent;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEvent;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEventCodec;
import com.kgiannopoulou.ledgerforge.shared.event.PaymentCapturedEvent;
import com.kgiannopoulou.ledgerforge.shared.event.PaymentRefundedEvent;
import com.kgiannopoulou.ledgerforge.shared.event.TransferCompletedEvent;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDeliveryRepository;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookNotification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Notifies merchants about captures and refunds of their payments.
 *
 * <p>Order inside one transaction: claim the event id, send, record the delivery. If sending
 * fails, the claim rolls back and the retry sends again. If sending succeeds but the commit
 * fails (crash), the merchant may get the webhook twice, which is why it carries the
 * {@code eventId}. Exactly-once delivery to an external HTTP endpoint is not achievable;
 * at-least-once plus receiver-side deduplication is the standard contract.
 */
@Service
public class WebhookNotificationService {

    public static final String CONSUMER = "merchant-webhooks";

    private final ProcessedEventLog processedEvents;
    private final WebhookSender sender;
    private final WebhookDeliveryRepository deliveries;
    private final IntegrationEventCodec codec;
    private final Clock clock;

    public WebhookNotificationService(ProcessedEventLog processedEvents, WebhookSender sender,
                                      WebhookDeliveryRepository deliveries, IntegrationEventCodec codec, Clock clock) {
        this.processedEvents = processedEvents;
        this.sender = sender;
        this.deliveries = deliveries;
        this.codec = codec;
        this.clock = clock;
    }

    @Transactional
    public void handle(IntegrationEvent event) {
        WebhookNotification notification = switch (event) {
            case PaymentCapturedEvent p -> notification(p, p.merchantAccountId(), p.paymentId());
            case PaymentRefundedEvent r -> notification(r, r.merchantAccountId(), r.paymentId());
            case TransferCompletedEvent ignored -> null;
            case AccountFrozenEvent ignored -> null;
        };
        if (notification == null || !processedEvents.firstDelivery(CONSUMER, event.eventId())) {
            return;
        }
        sender.send(notification);
        deliveries.recordDelivered(notification, clock.instant());
    }

    private WebhookNotification notification(IntegrationEvent event, java.util.UUID merchantAccountId,
                                             java.util.UUID paymentId) {
        return new WebhookNotification(event.eventId(), event.type(), merchantAccountId, paymentId,
                codec.encode(event));
    }
}
