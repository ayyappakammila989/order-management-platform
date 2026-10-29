package com.retail.oms.pricing.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * JSON values in Redis with a TTL. Redis is only an accelerator: when it is down or slow, or a value cannot be
 * read, the call behaves like a cache miss and the caller goes to the source of truth.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PricingCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public <T> Optional<T> get(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Cache read failed for {}: {}", key, e.toString());
            return Optional.empty();
        }
    }

    public void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Cache write failed for {}: {}", key, e.toString());
        }
    }

    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Cache evict failed for {}: {}", key, e.toString());
        }
    }
}
