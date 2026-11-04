package com.kgiannopoulou.ledgerforge.webhook.infrastructure;

import com.kgiannopoulou.ledgerforge.webhook.domain.ClaimedDelivery;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookAttempt;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookAttemptOutcome;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDelivery;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDeliveryRepository;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookDeliveryStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcWebhookDeliveryRepository implements WebhookDeliveryRepository {

    private static final String COLUMNS = """
            id, endpoint_id, event_id, event_type, payload, status, attempts, next_attempt_at, last_error,
            created_at, delivered_at""";

    private final JdbcClient jdbc;

    JdbcWebhookDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(UUID endpointId, UUID eventId, String eventType, String payload, Instant now) {
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO webhook_deliveries (id, endpoint_id, event_id, event_type, payload, status,
                                                        next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                        ON CONFLICT (endpoint_id, event_id) DO NOTHING""")
                .params(UUID.randomUUID(), endpointId, eventId, eventType, payload, at, at)
                .update();
    }

    @Override
    public List<ClaimedDelivery> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT id FROM webhook_deliveries
                            WHERE status = 'PENDING' AND next_attempt_at <= ?
                            ORDER BY next_attempt_at
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE webhook_deliveries d
                        SET attempts = d.attempts + 1, next_attempt_at = ?
                        FROM due, webhook_endpoints e
                        WHERE d.id = due.id AND e.id = d.endpoint_id
                        RETURNING d.id, d.endpoint_id, d.event_id, d.payload, d.attempts,
                                  e.url, e.secret_ciphertext, e.status AS endpoint_status""")
                .params(now.atOffset(ZoneOffset.UTC), limit, leaseUntil.atOffset(ZoneOffset.UTC))
                .query((rs, row) -> new ClaimedDelivery(
                        rs.getObject("id", UUID.class),
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        rs.getString("url"),
                        rs.getString("secret_ciphertext"),
                        "ACTIVE".equals(rs.getString("endpoint_status"))))
                .list();
    }

    @Override
    public void recordAttempt(WebhookAttempt attempt) {
        jdbc.sql("""
                        INSERT INTO webhook_delivery_attempts (id, delivery_id, attempt_number, started_at, duration_ms,
                                                               outcome, response_status, error)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(attempt.id(), attempt.deliveryId(), attempt.attemptNumber(),
                        attempt.startedAt().atOffset(ZoneOffset.UTC), attempt.durationMs(), attempt.outcome().name(),
                        attempt.responseStatus(), attempt.error())
                .update();
    }

    @Override
    public void markDelivered(UUID id, int attemptNumber, Instant deliveredAt) {
        jdbc.sql("""
                        UPDATE webhook_deliveries SET status = 'DELIVERED', delivered_at = ?, last_error = NULL
                        WHERE id = ? AND attempts = ? AND status = 'PENDING'""")
                .params(deliveredAt.atOffset(ZoneOffset.UTC), id, attemptNumber)
                .update();
    }

    @Override
    public void scheduleRetry(UUID id, int attemptNumber, Instant nextAttemptAt, @Nullable String error) {
        jdbc.sql("""
                        UPDATE webhook_deliveries SET next_attempt_at = ?, last_error = ?
                        WHERE id = ? AND attempts = ? AND status = 'PENDING'""")
                .params(nextAttemptAt.atOffset(ZoneOffset.UTC), error, id, attemptNumber)
                .update();
    }

    @Override
    public void markDead(UUID id, int attemptNumber, @Nullable String error) {
        jdbc.sql("""
                        UPDATE webhook_deliveries SET status = 'DEAD', last_error = ?
                        WHERE id = ? AND attempts = ? AND status = 'PENDING'""")
                .params(error, id, attemptNumber)
                .update();
    }

    @Override
    public boolean requeueDead(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE webhook_deliveries SET status = 'PENDING', next_attempt_at = ?
                        WHERE id = ? AND status = 'DEAD'""")
                .params(now.atOffset(ZoneOffset.UTC), id)
                .update() == 1;
    }

    @Override
    public Optional<WebhookDelivery> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_deliveries WHERE id = ?")
                .param(id).query(JdbcWebhookDeliveryRepository::map).optional();
    }

    @Override
    public List<WebhookDelivery> findByEndpointId(UUID endpointId, @Nullable WebhookDeliveryStatus status, int limit) {
        if (status == null) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_deliveries WHERE endpoint_id = ? ORDER BY created_at DESC LIMIT ?")
                    .params(endpointId, limit).query(JdbcWebhookDeliveryRepository::map).list();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_deliveries WHERE endpoint_id = ? AND status = ? ORDER BY created_at DESC LIMIT ?")
                .params(endpointId, status.name(), limit).query(JdbcWebhookDeliveryRepository::map).list();
    }

    @Override
    public List<WebhookAttempt> findAttempts(UUID deliveryId) {
        return jdbc.sql("""
                        SELECT id, delivery_id, attempt_number, started_at, duration_ms, outcome, response_status, error
                        FROM webhook_delivery_attempts WHERE delivery_id = ? ORDER BY attempt_number""")
                .param(deliveryId)
                .query((rs, row) -> new WebhookAttempt(
                        rs.getObject("id", UUID.class),
                        rs.getObject("delivery_id", UUID.class),
                        rs.getInt("attempt_number"),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("duration_ms"),
                        WebhookAttemptOutcome.valueOf(rs.getString("outcome")),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("error")))
                .list();
    }

    @Override
    public Optional<UUID> findOwnerAccountId(UUID deliveryId) {
        return jdbc.sql("""
                        SELECT e.account_id FROM webhook_deliveries d
                        JOIN webhook_endpoints e ON e.id = d.endpoint_id
                        WHERE d.id = ?""")
                .param(deliveryId).query(UUID.class).optional();
    }

    private static WebhookDelivery map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime deliveredAt = rs.getObject("delivered_at", OffsetDateTime.class);
        return new WebhookDelivery(
                rs.getObject("id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("payload"),
                WebhookDeliveryStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                rs.getString("last_error"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                deliveredAt == null ? null : deliveredAt.toInstant());
    }
}
