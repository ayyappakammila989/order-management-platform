package com.retail.oms.order.infrastructure.redis;

import com.retail.oms.order.application.IdempotencyStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Keys {@code idem:http:<Idempotency-Key>} (architecture 8), TTL 24 h. The claim is
 * {@code SET key value NX EX ttl}: check and set in one atomic step, so two parallel requests cannot both win.
 * Values: {@code IN_PROGRESS|<fingerprint>} or {@code DONE|<fingerprint>|<orderId>}.
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    static final String PREFIX = "idem:http:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisIdempotencyStore(StringRedisTemplate redis, @Value("${oms.order.idempotency-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Optional<Entry> find(String key) {
        return Optional.ofNullable(redis.opsForValue().get(PREFIX + key)).map(RedisIdempotencyStore::parse);
    }

    @Override
    public boolean tryStart(String key, String fingerprint) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + key, "IN_PROGRESS|" + fingerprint, ttl));
    }

    @Override
    public void complete(String key, String fingerprint, UUID orderId) {
        redis.opsForValue().set(PREFIX + key, "DONE|" + fingerprint + "|" + orderId, ttl);
    }

    @Override
    public void release(String key) {
        redis.delete(PREFIX + key);
    }

    private static Entry parse(String value) {
        String[] parts = value.split("\\|");
        return "DONE".equals(parts[0])
                ? new Entry(true, parts[1], UUID.fromString(parts[2]))
                : new Entry(false, parts[1], null);
    }
}
