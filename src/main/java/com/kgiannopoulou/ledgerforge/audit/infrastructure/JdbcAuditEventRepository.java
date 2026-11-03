package com.kgiannopoulou.ledgerforge.audit.infrastructure;

import com.kgiannopoulou.ledgerforge.audit.domain.AuditAction;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditEvent;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditEventRepository;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditedEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plain JDBC, insert-only. Runs on the same connection as the surrounding JPA transaction
 * ({@code JpaTransactionManager} exposes it to {@code JdbcClient}), so the insert commits or rolls
 * back with the business change.
 */
@Repository
class JdbcAuditEventRepository implements AuditEventRepository {

    private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    JdbcAuditEventRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.sql("""
                        INSERT INTO audit_events (id, actor, action, entity_type, entity_id, occurred_at, trace_id, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)""")
                .params(event.id(), event.actor(), event.action().name(), event.entityType().name(), event.entityId(),
                        event.occurredAt().atOffset(ZoneOffset.UTC), event.traceId(),
                        jsonMapper.writeValueAsString(event.metadata()))
                .update();
    }

    @Override
    public Page<AuditEvent> search(Query query, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (query.entityType() != null) {
            where.append(" AND entity_type = :entityType");
            params.put("entityType", query.entityType().name());
        }
        if (query.entityId() != null) {
            where.append(" AND entity_id = :entityId");
            params.put("entityId", query.entityId());
        }
        if (query.actor() != null) {
            where.append(" AND actor = :actor");
            params.put("actor", query.actor());
        }
        if (query.action() != null) {
            where.append(" AND action = :action");
            params.put("action", query.action().name());
        }
        long total = jdbc.sql("SELECT count(*) FROM audit_events" + where).params(params)
                .query(Long.class).single();
        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("limit", pageable.getPageSize());
        pageParams.put("offset", pageable.getOffset());
        List<AuditEvent> events = new ArrayList<>(jdbc.sql("""
                        SELECT id, actor, action, entity_type, entity_id, occurred_at, trace_id, metadata::text AS metadata
                        FROM audit_events""" + where + " ORDER BY occurred_at DESC, id LIMIT :limit OFFSET :offset")
                .params(pageParams)
                .query(this::map)
                .list());
        return new PageImpl<>(events, pageable, total);
    }

    private AuditEvent map(ResultSet rs, int row) throws SQLException {
        return new AuditEvent(
                rs.getObject("id", UUID.class),
                rs.getString("actor"),
                AuditAction.valueOf(rs.getString("action")),
                AuditedEntity.valueOf(rs.getString("entity_type")),
                rs.getObject("entity_id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("trace_id"),
                jsonMapper.readValue(rs.getString("metadata"), METADATA));
    }
}
