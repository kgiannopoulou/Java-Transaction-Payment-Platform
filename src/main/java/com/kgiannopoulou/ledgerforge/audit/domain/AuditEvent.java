package com.kgiannopoulou.ledgerforge.audit.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable audit record. A record (not a JPA entity) on purpose: there is no managed
 * object whose setters or dirty checking could ever turn into an {@code UPDATE}.
 *
 * @param actor    the authenticated principal ({@code sub} claim), or {@code system}
 * @param traceId  the request's trace id, linking the event to logs and the client's error report
 * @param metadata action-specific details (amounts, counterparties); never secrets or PII
 */
public record AuditEvent(
        UUID id,
        String actor,
        AuditAction action,
        AuditedEntity entityType,
        UUID entityId,
        Instant occurredAt,
        @Nullable String traceId,
        Map<String, Object> metadata) {

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        metadata = Map.copyOf(metadata);
    }
}
