package com.kgiannopoulou.ledgerforge.messaging.infrastructure;

import com.kgiannopoulou.ledgerforge.messaging.domain.ProcessedEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
class JdbcProcessedEventRepository implements ProcessedEventRepository {

    private final JdbcClient jdbc;

    JdbcProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(String consumer, UUID eventId, Instant processedAt) {
        return jdbc.sql("""
                        INSERT INTO processed_events (consumer, event_id, processed_at) VALUES (?, ?, ?)
                        ON CONFLICT (consumer, event_id) DO NOTHING""")
                .params(consumer, eventId, processedAt.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }
}
