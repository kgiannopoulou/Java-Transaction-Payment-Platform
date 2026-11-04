package com.kgiannopoulou.ledgerforge.infrastructure;

import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStore;
import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStoreUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Sliding windows as Redis sorted sets: one member per hit, scored by time.
 *
 * <p>Lua makes check-and-add a single atomic step on the server. A separate read and write would let
 * concurrent requests all see "99" and all pass. Time comes from Redis too, so instances with drifting
 * clocks still share one window. Every key has an expiry, so nothing here can outlive its purpose.
 */
@Component
class RedisEphemeralStore implements EphemeralStore {

    /** KEYS[1] window; ARGV window ms, limit (0 = none), unique member. Returns allowed, count, retry-after ms. */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> HIT = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            local window = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            local count = redis.call('ZCARD', KEYS[1])
            if limit > 0 and count >= limit then
                local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
                return {0, count, tonumber(oldest[2]) + window - now}
            end
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], window)
            return {1, count + 1, 0}
            """, List.class);

    private static final RedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - tonumber(ARGV[1]))
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;

    RedisEphemeralStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Acquisition tryAcquire(String key, int limit, Duration window) {
        List<?> result = hit(key, limit, window);
        return new Acquisition(((Number) result.get(0)).longValue() == 1, ((Number) result.get(1)).longValue(),
                Duration.ofMillis(((Number) result.get(2)).longValue()));
    }

    @Override
    public long increment(String key, Duration window) {
        return ((Number) hit(key, 0, window).get(1)).longValue();
    }

    @Override
    public long count(String key, Duration window) {
        return guarded(() -> redis.execute(COUNT, List.of(key), Long.toString(window.toMillis())));
    }

    @Override
    public Optional<String> get(String key) {
        return guarded(() -> Optional.ofNullable(redis.opsForValue().get(key)));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        guarded(() -> {
            redis.opsForValue().set(key, value, ttl);
            return null;
        });
    }

    private List<?> hit(String key, int limit, Duration window) {
        return guarded(() -> redis.execute(HIT, List.of(key), Long.toString(window.toMillis()),
                Integer.toString(limit), UUID.randomUUID().toString()));
    }

    private static <T> T guarded(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            throw new EphemeralStoreUnavailableException(e);
        }
    }
}
