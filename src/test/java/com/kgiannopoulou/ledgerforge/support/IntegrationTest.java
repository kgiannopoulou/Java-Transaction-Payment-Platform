package com.kgiannopoulou.ledgerforge.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@link ApiTestSupport} against the PostgreSQL container shared by the whole test run. */
public abstract class IntegrationTest extends ApiTestSupport {

    @DynamicPropertySource
    static void sharedDatabase(DynamicPropertyRegistry registry) {
        PostgresContainers.register(registry, PostgresContainers.SHARED);
        RedisContainers.register(registry, RedisContainers.SHARED);
    }
}
