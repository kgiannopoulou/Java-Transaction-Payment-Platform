package com.kgiannopoulou.ledgerforge.outbox.infrastructure;

import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxMessage;
import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class JdbcOutboxStore implements OutboxStore {

    private final JdbcClient jdbc;

    JdbcOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(OutboxMessage message) {
        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload,
                                                   created_at, next_attempt_at)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)""")
                .params(message.id(), message.aggregateType(), message.aggregateId(), message.eventType(),
                        message.topic(), message.payload(), utc(message.createdAt()), utc(message.createdAt()))
                .update();
    }

    @Override
    public List<OutboxMessage> lockNextBatch(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT id, aggregate_type, aggregate_id, event_type, topic, payload::text AS payload,
                               created_at, attempts
                        FROM outbox_events o
                        WHERE published_at IS NULL AND next_attempt_at <= ?
                          -- per-aggregate ordering: never overtake an earlier, still unpublished
                          -- event of the same aggregate (e.g. one that is backing off after a failure)
                          AND NOT EXISTS (SELECT 1 FROM outbox_events earlier
                                          WHERE earlier.aggregate_id = o.aggregate_id
                                            AND earlier.published_at IS NULL
                                            AND earlier.sequence_number < o.sequence_number)
                        ORDER BY sequence_number
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED""")
                .params(utc(now), limit)
                .query(JdbcOutboxStore::map)
                .list();
    }

    @Override
    public void markPublished(UUID id, Instant publishedAt) {
        jdbc.sql("UPDATE outbox_events SET published_at = ?, last_error = NULL WHERE id = ?")
                .params(utc(publishedAt), id)
                .update();
    }

    @Override
    public void markFailed(UUID id, Instant nextAttemptAt, String error) {
        jdbc.sql("UPDATE outbox_events SET attempts = attempts + 1, next_attempt_at = ?, last_error = ? WHERE id = ?")
                .params(utc(nextAttemptAt), error, id)
                .update();
    }

    @Override
    public int deletePublishedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < ?")
                .param(utc(cutoff))
                .update();
    }

    @Override
    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single();
    }

    private static OutboxMessage map(ResultSet rs, int row) throws SQLException {
        return new OutboxMessage(
                rs.getObject("id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("topic"),
                rs.getString("payload"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getInt("attempts"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
