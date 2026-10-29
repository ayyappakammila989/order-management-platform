package com.retail.oms.cart.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.cart.application.CartService;
import com.retail.oms.cart.application.CartStore;
import com.retail.oms.cart.config.CartProperties;
import com.retail.oms.cart.domain.Cart;
import com.retail.oms.cart.exception.CartConflictException;
import com.retail.oms.cart.infrastructure.client.OrderClient;
import com.retail.oms.cart.infrastructure.redis.RedisCartStore;
import com.retail.oms.cart.infrastructure.redis.RedisCheckoutRecordStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Redis: the compare-and-set Lua script, the 7-day sliding expiry and concurrent changes to one cart. */
@Testcontainers
class RedisCartStoreIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static StringRedisTemplate redis;
    CartProperties props = new CartProperties(Duration.ofDays(7), Duration.ofHours(24), 50, 999, "USD");
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RedisCartStore store;
    CartService service;

    @BeforeAll
    static void connect() {
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        store = new RedisCartStore(redis, mapper, props);
        service = new CartService(store, props, Clock.systemUTC());
    }

    @Test
    void createAndFindRoundTripWithTheSevenDayExpiry() {
        Cart cart = Cart.empty("cust-1", "USD", Instant.parse("2026-10-29T10:00:00Z")).addItem("SKU-1", 2, Instant.parse("2026-10-29T10:00:00Z"));
        store.create(cart);

        CartStore.VersionedCart found = store.find(cart.cartId()).orElseThrow();
        assertThat(found.cart()).isEqualTo(cart);
        assertThat(found.version()).isEqualTo(1);
        assertThat(redis.getExpire("cart:" + cart.cartId())).isBetween(604_790L, 604_800L);
    }

    @Test
    void readingASlidingExpiryPushesTheDeadlineOutAgain() {
        Cart cart = service.create("cust-1", "USD");
        redis.expire("cart:" + cart.cartId(), Duration.ofSeconds(100));

        service.get(cart.cartId(), "cust-1");

        assertThat(redis.getExpire("cart:" + cart.cartId())).isGreaterThan(604_000L);
    }

    @Test
    void aWriteWithAStaleVersionIsRejectedAndChangesNothing() {
        Cart cart = service.create("cust-1", "USD");
        long v1 = store.find(cart.cartId()).orElseThrow().version();

        assertThat(store.replace(cart.addItem("SKU-1", 1, Instant.now()), v1)).isTrue();
        assertThat(store.replace(cart.addItem("SKU-2", 1, Instant.now()), v1)).isFalse();     // still based on version 1

        CartStore.VersionedCart now = store.find(cart.cartId()).orElseThrow();
        assertThat(now.version()).isEqualTo(2);
        assertThat(now.cart().items()).extracting("sku").containsExactly("SKU-1");
    }

    @Test
    void replacingADeletedCartDoesNotBringItBack() {
        Cart cart = service.create("cust-1", "USD");
        store.delete(cart.cartId());

        assertThat(store.replace(cart.addItem("SKU-1", 1, Instant.now()), 1)).isFalse();
        assertThat(store.find(cart.cartId())).isEmpty();
    }

    @Test
    void eightParallelAddsToTheSameCartAreAllKept() throws Exception {
        Cart cart = service.create("cust-1", "USD");

        runParallel(8, i -> service.addItem(cart.cartId(), "cust-1", "SKU-" + i, 1));

        // 8 writers can lose at most 7 races each, and the service retries 10 times, so none may be lost
        assertThat(service.get(cart.cartId(), "cust-1").items()).hasSize(8);
    }

    @Test
    void underHeavyContentionNoUpdateIsLostSilently() throws Exception {
        Cart cart = service.create("cust-1", "USD");

        List<Boolean> outcomes = runParallel(40, i -> service.addItem(cart.cartId(), "cust-1", "SKU-" + i, 1));

        long succeeded = outcomes.stream().filter(ok -> ok).count();
        // a request either succeeded (and its item is in the cart) or was told to retry (CRT-409): never both lost
        assertThat(service.get(cart.cartId(), "cust-1").items()).hasSize((int) succeeded);
        assertThat(succeeded).isGreaterThanOrEqualTo(10);
    }

    @Test
    void sameSkuAddedInParallelSumsTheQuantities() throws Exception {
        Cart cart = service.create("cust-1", "USD");

        runParallel(8, i -> service.addItem(cart.cartId(), "cust-1", "SKU-1", 3));

        assertThat(service.get(cart.cartId(), "cust-1").items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(24));
    }

    @Test
    void checkoutRecordIsWrittenOnceAndExpiresAfter24Hours() {
        var records = new RedisCheckoutRecordStore(redis, mapper, props);
        var request = new OrderClient.OrderRequest("cust-1", "WEB", "USD", List.of(), null,
                new java.math.BigDecimal("4.99"), new java.math.BigDecimal("4.99"),
                new OrderClient.OrderRequest.PaymentMethod("PAYPAL", "PAYPAL-ORDER-1"));

        assertThat(records.saveRequest("cust-1", "checkout-0001", "cart-a", request)).isTrue();
        assertThat(records.saveRequest("cust-1", "checkout-0001", "cart-b", request)).isFalse();
        assertThat(records.find("cust-1", "checkout-0001").orElseThrow().cartId()).isEqualTo("cart-a");
        assertThat(redis.getExpire("checkout:cust-1:checkout-0001")).isBetween(86_390L, 86_400L);
        assertThat(records.find("cust-2", "checkout-0001")).isEmpty();      // keys are per customer
    }

    private List<Boolean> runParallel(int threads, java.util.function.IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    task.accept(n);
                    return true;
                } catch (CartConflictException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }
}
