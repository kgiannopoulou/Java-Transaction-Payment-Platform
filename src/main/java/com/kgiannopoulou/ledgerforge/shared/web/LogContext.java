package com.kgiannopoulou.ledgerforge.shared.web;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Identifiers attached to every log line of the current request, so that one query finds the whole
 * story of a payment. Written to the MDC; {@code TraceIdFilter} clears it when the request ends, so
 * values are still there for the exception handler and cannot leak into the next request.
 *
 * <p>Only opaque ids go here: never names, emails, amounts, tokens or secrets. See docs/logging.md.
 */
public final class LogContext {

    public static final String CUSTOMER_ID = "customerId";
    public static final String PAYMENT_ID = "paymentId";
    public static final String TRANSFER_ID = "transferId";
    public static final String EVENT_ID = "eventId";
    public static final String DELIVERY_ID = "deliveryId";

    private LogContext() {
    }

    public static void put(String key, UUID value) {
        MDC.put(key, value.toString());
    }
}
