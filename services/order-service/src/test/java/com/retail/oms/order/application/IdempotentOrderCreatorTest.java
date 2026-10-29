package com.retail.oms.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.retail.oms.order.domain.Order;
import com.retail.oms.order.exception.IdempotencyInProgressException;
import com.retail.oms.order.exception.IdempotencyKeyReusedException;
import com.retail.oms.order.support.TestOrders;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class IdempotentOrderCreatorTest {

    @Mock OrderService orderService;
    @Mock CreateOrderCommand command;

    InMemoryStore store = new InMemoryStore();
    IdempotentOrderCreator creator;
    Order order = TestOrders.order("cust-1");

    @BeforeEach
    void setUp() {
        creator = new IdempotentOrderCreator(orderService, store);
    }

    @Test
    void secondCallWithSameKeyReturnsStoredOrderAndCreatesOnlyOnce() {
        when(orderService.create(command, "key-1", "cart-service")).thenReturn(new OrderService.CreateResult(order, false));
        when(orderService.get(order.getOrderId())).thenReturn(order);

        OrderService.CreateResult first = creator.create("key-1", "fp-A", command, "cart-service");
        OrderService.CreateResult second = creator.create("key-1", "fp-A", command, "cart-service");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.order().getOrderId()).isEqualTo(first.order().getOrderId());
        verify(orderService, times(1)).create(any(), anyString(), anyString());
        assertThat(store.entries.get("key-1")).isEqualTo(new IdempotencyStore.Entry(true, "fp-A", order.getOrderId()));
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        store.complete("key-2", "fp-A", order.getOrderId());

        assertThatThrownBy(() -> creator.create("key-2", "fp-B", command, "cart-service"))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .hasFieldOrPropertyWithValue("errorCode", "IDM-422");
        verifyNoInteractions(orderService);
    }

    @Test
    void parallelDuplicateWhileFirstIsRunningGetsConflict() {
        store.tryStart("key-3", "fp-A");

        assertThatThrownBy(() -> creator.create("key-3", "fp-A", command, "cart-service"))
                .isInstanceOf(IdempotencyInProgressException.class)
                .hasFieldOrPropertyWithValue("errorCode", "IDM-409");
        verifyNoInteractions(orderService);
    }

    @Test
    void failedCreateReleasesKeySoClientCanRetry() {
        when(orderService.create(command, "key-4", "cart-service")).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> creator.create("key-4", "fp-A", command, "cart-service"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.entries).doesNotContainKey("key-4");
    }

    @Test
    void redisDownFallsBackToDatabaseKey() {
        IdempotencyStore broken = mock(IdempotencyStore.class);
        when(broken.find(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(orderService.create(command, "key-5", "cart-service")).thenReturn(new OrderService.CreateResult(order, false));

        OrderService.CreateResult result = new IdempotentOrderCreator(orderService, broken)
                .create("key-5", "fp-A", command, "cart-service");

        assertThat(result.order()).isSameAs(order);
    }

    @Test
    void concurrentInsertLosingOnUniqueKeyReturnsTheWinnersOrder() {
        when(orderService.create(command, "key-6", "cart-service"))
                .thenThrow(new DataIntegrityViolationException("uq_orders_idempotency_key"))
                .thenReturn(new OrderService.CreateResult(order, true));

        OrderService.CreateResult result = creator.create("key-6", "fp-A", command, "cart-service");

        assertThat(result.replayed()).isTrue();
        verify(orderService, times(2)).create(eq(command), eq("key-6"), anyString());
    }

    /** Same semantics as RedisIdempotencyStore (that one is tested against real Redis in RedisIdempotencyStoreIT). */
    static class InMemoryStore implements IdempotencyStore {
        final Map<String, Entry> entries = new HashMap<>();

        @Override
        public Optional<Entry> find(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        @Override
        public boolean tryStart(String key, String fingerprint) {
            return entries.putIfAbsent(key, new Entry(false, fingerprint, null)) == null;
        }

        @Override
        public void complete(String key, String fingerprint, UUID orderId) {
            entries.put(key, new Entry(true, fingerprint, orderId));
        }

        @Override
        public void release(String key) {
            entries.remove(key);
        }
    }
}
