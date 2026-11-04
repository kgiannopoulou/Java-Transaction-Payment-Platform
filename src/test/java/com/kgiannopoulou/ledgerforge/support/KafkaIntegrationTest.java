package com.kgiannopoulou.ledgerforge.support;

import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * {@link ApiTestSupport} with the whole event pipeline running: shared PostgreSQL, a real Kafka
 * broker (KRaft, single node), the outbox relay polling every 100 ms, the risk and webhook
 * consumers, the webhook dispatcher, and short retry backoffs so failure tests finish quickly.
 */
@Tag("e2e")
@Import(KafkaTestConfiguration.class)
public abstract class KafkaIntegrationTest extends ApiTestSupport {

    protected static final KafkaContainer KAFKA = startKafka();

    private static KafkaContainer startKafka() {
        KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");
        kafka.start();
        return kafka;
    }

    @DynamicPropertySource
    static void eventPipeline(DynamicPropertyRegistry registry) {
        PostgresContainers.register(registry, PostgresContainers.SHARED);
        RedisContainers.register(registry, RedisContainers.SHARED);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> "true");
        registry.add("ledgerforge.outbox.relay.enabled", () -> "true");
        registry.add("ledgerforge.outbox.relay.poll-interval-ms", () -> "100");
        registry.add("ledgerforge.kafka.consumers.enabled", () -> "true");
        registry.add("ledgerforge.webhooks.dispatcher.enabled", () -> "true");
        registry.add("ledgerforge.webhooks.dispatcher.poll-interval-ms", () -> "100");
        registry.add("ledgerforge.kafka.retry.initial-interval", () -> "100ms");
        registry.add("ledgerforge.kafka.retry.max-interval", () -> "400ms");
        registry.add("ledgerforge.kafka.retry.max-retries", () -> "3");
    }
}
