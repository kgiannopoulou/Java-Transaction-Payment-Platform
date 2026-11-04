package com.kgiannopoulou.ledgerforge.audit.domain;

/** The kind of entity an audit event is about. */
public enum AuditedEntity {
    CUSTOMER,
    ACCOUNT,
    TRANSFER,
    PAYMENT,
    WEBHOOK_ENDPOINT
}
