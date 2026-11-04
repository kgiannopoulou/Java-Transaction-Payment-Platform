package com.kgiannopoulou.ledgerforge.webhook.application;

import com.kgiannopoulou.ledgerforge.shared.event.AccountFrozenEvent;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEvent;
import com.kgiannopoulou.ledgerforge.shared.event.PaymentCapturedEvent;
import com.kgiannopoulou.ledgerforge.shared.event.PaymentRefundedEvent;
import com.kgiannopoulou.ledgerforge.shared.event.TransferCompletedEvent;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The public webhook envelope:
 *
 * <pre>{@code
 * {
 *   "id": "evt_6f1c...",
 *   "type": "payment.captured",
 *   "createdAt": "2026-11-04T10:00:00Z",
 *   "data": { ... }
 * }
 * }</pre>
 *
 * This is a separate contract from the internal Kafka events: those can change with the code,
 * whereas customers build against this shape. {@code id} is stable across retries, so receivers
 * can deduplicate on it.
 */
public record WebhookEvent(String id, String type, Instant createdAt, Map<String, Object> data) {

    /** An event with the accounts whose endpoints should receive it. */
    public record Routed(WebhookEvent event, WebhookEventType type, List<UUID> accountIds) {
    }

    public static String publicId(UUID eventId) {
        return "evt_" + eventId.toString().replace("-", "");
    }

    /** Exhaustive over the sealed event hierarchy: a new event will not compile until it is mapped. */
    public static Routed from(IntegrationEvent event) {
        return switch (event) {
            case PaymentCapturedEvent e -> routed(e, WebhookEventType.PAYMENT_CAPTURED,
                    List.of(e.merchantAccountId(), e.payerAccountId()),
                    payment(e.paymentId(), e.payerAccountId(), e.merchantAccountId(), e.amount(), e.currency().getCurrencyCode()));
            case PaymentRefundedEvent e -> {
                Map<String, Object> data = payment(e.paymentId(), e.payerAccountId(), e.merchantAccountId(), e.amount(),
                        e.currency().getCurrencyCode());
                data.put("fullyRefunded", e.fullyRefunded());
                yield routed(e, WebhookEventType.PAYMENT_REFUNDED,
                        List.of(e.merchantAccountId(), e.payerAccountId()), data);
            }
            case TransferCompletedEvent e -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("transferId", e.transferId());
                data.put("sourceAccountId", e.sourceAccountId());
                data.put("destinationAccountId", e.destinationAccountId());
                data.put("amount", e.amount().toPlainString());
                data.put("currency", e.currency().getCurrencyCode());
                yield routed(e, WebhookEventType.TRANSFER_COMPLETED,
                        List.of(e.sourceAccountId(), e.destinationAccountId()), data);
            }
            case AccountFrozenEvent e -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("accountId", e.accountId());
                yield routed(e, WebhookEventType.ACCOUNT_FROZEN, List.of(e.accountId()), data);
            }
        };
    }

    private static Map<String, Object> payment(UUID paymentId, UUID payer, UUID merchant, BigDecimal amount,
                                               String currency) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", paymentId);
        data.put("payerAccountId", payer);
        data.put("merchantAccountId", merchant);
        data.put("amount", amount.toPlainString()); // a string, so no JSON client rounds it through a double
        data.put("currency", currency);
        return data;
    }

    private static Routed routed(IntegrationEvent event, WebhookEventType type, List<UUID> accountIds,
                                 Map<String, Object> data) {
        return new Routed(new WebhookEvent(publicId(event.eventId()), type.wireName(), event.occurredAt(), data),
                type, accountIds);
    }
}
