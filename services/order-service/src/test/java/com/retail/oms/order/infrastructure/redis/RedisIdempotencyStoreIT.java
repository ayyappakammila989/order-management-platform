package com.retail.oms.order.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.retail.oms.order.application.IdempotencyStore;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** SETNX semantics and TTLs against a real Redis (architecture 12.2: "Redis: TTLs, SETNX idempotency"). */
@Testcontainers
class RedisIdempotencyStoreIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;
    static RedisIdempotencyStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        store = new RedisIdempotencyStore(redis, Duration.ofHours(24));
    }

    @AfterAll
    static void close() {
        connectionFactory.destroy();
    }

    @Test
    void firstClaimWinsSecondLoses() {
        String key = UUID.randomUUID().toString();

        assertThat(store.tryStart(key, "fp")).isTrue();
        assertThat(store.tryStart(key, "fp")).isFalse();
        assertThat(store.find(key)).contains(new IdempotencyStore.Entry(false, "fp", null));
    }

    @Test
    void keysExpireAfter24Hours() {
        String key = UUID.randomUUID().toString();
        UUID orderId = UUID.randomUUID();

        store.tryStart(key, "fp");
        long ttlInProgress = redis.getExpire("idem:http:" + key);
        store.complete(key, "fp", orderId);
        long ttlDone = redis.getExpire("idem:http:" + key);

        assertThat(ttlInProgress).isBetween(86_390L, 86_400L);
        assertThat(ttlDone).isBetween(86_390L, 86_400L);
        assertThat(redis.opsForValue().get("idem:http:" + key)).isEqualTo("DONE|fp|" + orderId);
        assertThat(store.find(key)).contains(new IdempotencyStore.Entry(true, "fp", orderId));
    }

    @Test
    void releaseFreesTheKey() {
        String key = UUID.randomUUID().toString();
        store.tryStart(key, "fp");

        store.release(key);

        assertThat(store.find(key)).isEmpty();
        assertThat(store.tryStart(key, "fp")).isTrue();
    }

    @Test
    void exactlyOneOfTwentyParallelClaimsWins() throws Exception {
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            var claims = IntStream.range(0, 20)
                    .mapToObj(i -> (Callable<Boolean>) () -> store.tryStart(key, "fp")).toList();
            long winners = 0;
            for (Future<Boolean> f : pool.invokeAll(claims)) {
                if (f.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }
}
