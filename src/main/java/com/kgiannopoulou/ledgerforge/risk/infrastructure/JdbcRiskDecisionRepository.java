package com.kgiannopoulou.ledgerforge.risk.infrastructure;

import com.kgiannopoulou.ledgerforge.risk.domain.RiskDecision;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskDecisionRepository;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskOutcome;
import com.kgiannopoulou.ledgerforge.risk.domain.StoredRiskDecision;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@Repository
class JdbcRiskDecisionRepository implements RiskDecisionRepository {

    private static final String COLUMNS = """
            id, payment_id, payer_account_id, merchant_account_id, amount, currency, outcome, reasons::text AS reasons,
            degraded, created_at""";

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    JdbcRiskDecisionRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void save(StoredRiskDecision decision) {
        jdbc.sql("""
                        INSERT INTO risk_decisions (id, payment_id, payer_account_id, merchant_account_id, amount, currency,
                                                    outcome, reasons, degraded, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""")
                .params(decision.id(), decision.paymentId(), decision.payerAccountId(), decision.merchantAccountId(),
                        decision.amount(), decision.currency().getCurrencyCode(), decision.outcome().name(),
                        jsonMapper.writeValueAsString(decision.reasons()), decision.degraded(),
                        decision.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public List<StoredRiskDecision> findRecent(@Nullable RiskOutcome outcome, @Nullable UUID payerAccountId, int limit) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (outcome != null) {
            conditions.add("outcome = ?");
            params.add(outcome.name());
        }
        if (payerAccountId != null) {
            conditions.add("payer_account_id = ?");
            params.add(payerAccountId);
        }
        params.add(limit);
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        return jdbc.sql("SELECT " + COLUMNS + " FROM risk_decisions" + where + " ORDER BY created_at DESC LIMIT ?")
                .params(params).query(this::map).list();
    }

    private StoredRiskDecision map(ResultSet rs, int row) throws SQLException {
        return new StoredRiskDecision(
                rs.getObject("id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getObject("payer_account_id", UUID.class),
                rs.getObject("merchant_account_id", UUID.class),
                rs.getBigDecimal("amount"),
                Currency.getInstance(rs.getString("currency")),
                RiskOutcome.valueOf(rs.getString("outcome")),
                jsonMapper.readValue(rs.getString("reasons"), new TypeReference<List<RiskDecision>>() { }),
                rs.getBoolean("degraded"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
