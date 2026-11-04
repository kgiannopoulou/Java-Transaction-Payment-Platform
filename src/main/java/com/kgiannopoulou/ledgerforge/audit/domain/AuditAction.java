package com.kgiannopoulou.ledgerforge.audit.domain;

/** What happened. Stored as text; values are part of the audit contract and are never renamed. */
public enum AuditAction {
    CUSTOMER_REGISTERED,
    ACCOUNT_CREATED,
    ACCOUNT_FROZEN,
    ACCOUNT_UNFROZEN,
    ACCOUNT_CLOSED,
    DEPOSIT_RECORDED,
    TRANSFER_CREATED,
    PAYMENT_CREATED,
    PAYMENT_AUTHORIZED,
    PAYMENT_CAPTURED,
    PAYMENT_CANCELLED,
    PAYMENT_REFUNDED,
    WEBHOOK_ENDPOINT_REGISTERED,
    WEBHOOK_ENDPOINT_DISABLED
}
