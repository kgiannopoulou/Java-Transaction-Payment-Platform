package com.kgiannopoulou.ledgerforge.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/** Real Redis, for the same reason as real PostgreSQL: Lua scripts and expiry are Redis behaviour. */
public final class RedisContainers {

    private static final String IMAGE = "redis:7.4-alpine";
    private static final int PORT = 6379;

    /** Shared by all tests. Keys contain account ids, so tests do not interfere. */
    public static final GenericContainer<?> SHARED = start();

    private RedisContainers() {
    }

    public static GenericContainer<?> start() {
        GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(PORT);
        container.start();
        return container;
    }

    public static void register(DynamicPropertyRegistry registry, GenericContainer<?> container) {
        registry.add("spring.data.redis.host", container::getHost);
        registry.add("spring.data.redis.port", () -> container.getMappedPort(PORT));
        registry.add("spring.data.redis.timeout", () -> "500ms"); // containers on a busy machine can be slow
    }
}
