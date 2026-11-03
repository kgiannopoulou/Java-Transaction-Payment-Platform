package com.kgiannopoulou.ledgerforge.audit.domain;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Append and read. There is deliberately no update or delete operation: the audit trail is
 * append-only, and the database rejects such statements anyway.
 */
public interface AuditEventRepository {

    void append(AuditEvent event);

    Page<AuditEvent> search(Query query, Pageable pageable);

    /** All filters are optional and combined with AND. Results are newest first. */
    record Query(@Nullable AuditedEntity entityType, @Nullable UUID entityId,
                 @Nullable String actor, @Nullable AuditAction action) {
    }
}
