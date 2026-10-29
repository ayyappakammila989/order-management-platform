package com.retail.oms.query.infrastructure.redis;

import com.retail.oms.query.application.ProcessedEvents;
import com.retail.oms.query.config.QueryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis is a shortcut here, not the safety net: if it is unreachable we say "not seen" and process the event again,
 * which is harmless because the index writes are idempotent themselves.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisProcessedEvents implements ProcessedEvents {

    static final String PREFIX = "evt:order-query:";

    private final StringRedisTemplate redis;
    private final QueryProperties props;

    @Override
    public boolean contains(String eventId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + eventId));
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, treating event {} as not seen: {}", eventId, e.toString());
            return false;
        }
    }

    @Override
    public void markProcessed(String eventId) {
        try {
            redis.opsForValue().set(PREFIX + eventId, "1", props.processedEventTtl());
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, event {} not remembered: {}", eventId, e.toString());
        }
    }
}
