package com.kgiannopoulou.ledgerforge.shared.event;

/**
 * Kafka topic per aggregate type. Events are keyed by aggregate id, so all events of one
 * payment land on the same partition and are consumed in order.
 */
public final class EventTopics {

    public static final String PAYMENTS = "ledgerforge.payments.v1";
    public static final String TRANSFERS = "ledgerforge.transfers.v1";
    public static final String ACCOUNTS = "ledgerforge.accounts.v1";

    /** Records that failed all retries (or can never succeed) are parked on {@code <topic>.DLT}. */
    public static final String DLT_SUFFIX = ".DLT";

    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_EVENT_TYPE = "eventType";

    private EventTopics() {
    }

    public static String topicFor(IntegrationEvent event) {
        return switch (event) {
            case PaymentCapturedEvent ignored -> PAYMENTS;
            case PaymentRefundedEvent ignored -> PAYMENTS;
            case TransferCompletedEvent ignored -> TRANSFERS;
            case AccountFrozenEvent ignored -> ACCOUNTS;
        };
    }

    public static String aggregateTypeFor(IntegrationEvent event) {
        return switch (event) {
            case PaymentCapturedEvent ignored -> "PAYMENT";
            case PaymentRefundedEvent ignored -> "PAYMENT";
            case TransferCompletedEvent ignored -> "TRANSFER";
            case AccountFrozenEvent ignored -> "ACCOUNT";
        };
    }

    public static String deadLetterTopic(String topic) {
        return topic + DLT_SUFFIX;
    }
}
