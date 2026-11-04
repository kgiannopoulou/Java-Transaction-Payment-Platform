package com.kgiannopoulou.ledgerforge.webhook.infrastructure;

import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpoint;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpointRepository;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpointStatus;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEventType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
class JdbcWebhookEndpointRepository implements WebhookEndpointRepository {

    private static final String COLUMNS = "id, account_id, url, description, event_types, status, created_at";

    private final JdbcClient jdbc;

    JdbcWebhookEndpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(WebhookEndpoint endpoint, String secretCiphertext) {
        String[] eventTypes = endpoint.eventTypes().stream().map(WebhookEventType::wireName).toArray(String[]::new);
        jdbc.sql("""
                        INSERT INTO webhook_endpoints (id, account_id, url, description, event_types, secret_ciphertext,
                                                       status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(endpoint.id(), endpoint.accountId(), endpoint.url(), endpoint.description(), eventTypes,
                        secretCiphertext, endpoint.status().name(), endpoint.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<WebhookEndpoint> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_endpoints WHERE id = ?")
                .param(id).query(JdbcWebhookEndpointRepository::map).optional();
    }

    @Override
    public List<WebhookEndpoint> findByAccountId(UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_endpoints WHERE account_id = ? ORDER BY created_at DESC")
                .param(accountId).query(JdbcWebhookEndpointRepository::map).list();
    }

    @Override
    public List<WebhookEndpoint> findActiveSubscribers(Collection<UUID> accountIds, WebhookEventType type) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM webhook_endpoints
                        WHERE account_id = ANY (?) AND status = 'ACTIVE' AND ? = ANY (event_types)""")
                .params(accountIds.toArray(UUID[]::new), type.wireName())
                .query(JdbcWebhookEndpointRepository::map).list();
    }

    @Override
    public long countActive(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM webhook_endpoints WHERE account_id = ? AND status = 'ACTIVE'")
                .param(accountId).query(Long.class).single();
    }

    @Override
    public boolean disable(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE webhook_endpoints SET status = 'DISABLED', disabled_at = ?
                        WHERE id = ? AND status = 'ACTIVE'""")
                .params(now.atOffset(ZoneOffset.UTC), id).update() == 1;
    }

    private static WebhookEndpoint map(ResultSet rs, int row) throws SQLException {
        String[] wireNames = (String[]) rs.getArray("event_types").getArray();
        Set<WebhookEventType> eventTypes = Arrays.stream(wireNames)
                .map(name -> WebhookEventType.fromWireName(name).orElseThrow())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(WebhookEventType.class)));
        return new WebhookEndpoint(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("url"),
                rs.getString("description"),
                eventTypes,
                WebhookEndpointStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
