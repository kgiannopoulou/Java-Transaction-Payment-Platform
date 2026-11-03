package com.kgiannopoulou.ledgerforge.risk.infrastructure;

import com.kgiannopoulou.ledgerforge.risk.domain.RiskAlert;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskAlertRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@Repository
class JdbcRiskAlertRepository implements RiskAlertRepository {

    private static final String COLUMNS =
            "id, event_id, rule, account_id, reference_type, reference_id, amount, currency, created_at";

    private final JdbcClient jdbc;

    JdbcRiskAlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(RiskAlert alert) {
        jdbc.sql("INSERT INTO risk_alerts (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(alert.id(), alert.eventId(), alert.rule(), alert.accountId(), alert.referenceType(),
                        alert.referenceId(), alert.amount(), alert.currency().getCurrencyCode(),
                        alert.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public List<RiskAlert> findRecent(@Nullable UUID accountId, int limit) {
        if (accountId == null) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM risk_alerts ORDER BY created_at DESC LIMIT ?")
                    .param(limit).query(JdbcRiskAlertRepository::map).list();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM risk_alerts WHERE account_id = ? ORDER BY created_at DESC LIMIT ?")
                .params(accountId, limit).query(JdbcRiskAlertRepository::map).list();
    }

    private static RiskAlert map(ResultSet rs, int row) throws SQLException {
        return new RiskAlert(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("rule"),
                rs.getObject("account_id", UUID.class),
                rs.getString("reference_type"),
                rs.getObject("reference_id", UUID.class),
                rs.getBigDecimal("amount"),
                Currency.getInstance(rs.getString("currency")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
