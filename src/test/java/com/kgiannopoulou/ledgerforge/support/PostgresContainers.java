package com.kgiannopoulou.ledgerforge.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Singleton PostgreSQL containers (started once per JVM, reaped by Testcontainers' Ryuk).
 * A real PostgreSQL - not H2 - because the guarantees under test (row locks, deferred constraint
 * triggers, {@code ON CONFLICT}, isolation levels) are PostgreSQL behaviour.
 */
public final class PostgresContainers {

    private static final String IMAGE = "postgres:17-alpine";

    /** Shared by all well-behaved tests. */
    public static final PostgreSQLContainer SHARED = start();

    private PostgresContainers() {
    }

    public static PostgreSQLContainer start() {
        PostgreSQLContainer container = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("ledgerforge")
                .withUsername("ledgerforge")
                .withPassword("ledgerforge")
                .withCommand("postgres", "-c", "max_connections=300");
        container.start();
        return container;
    }

    public static void register(DynamicPropertyRegistry registry, PostgreSQLContainer container) {
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }
}
