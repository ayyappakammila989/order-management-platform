package com.retail.oms.order.application;

import com.retail.oms.order.exception.IdempotencyInProgressException;
import com.retail.oms.order.exception.IdempotencyKeyReusedException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Makes POST /internal/v1/orders safe to retry. Two layers:
 * <ol>
 *   <li>Redis key: a retry returns the order created by the first call; a parallel duplicate gets 409; the same key
 *       with a different body gets 422.</li>
 *   <li>Unique idempotency_key column: if Redis is down or lost the key, the database still refuses a second order,
 *       and OrderService returns the existing one.</li>
 * </ol>
 * Runs outside the database transaction on purpose: the Redis key is completed only after the order is committed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentOrderCreator {

    private final OrderService orderService;
    private final IdempotencyStore store;

    public OrderService.CreateResult create(String key, String fingerprint, CreateOrderCommand command,
            String createdBy) {
        boolean claimed;
        try {
            Optional<OrderService.CreateResult> earlier = replay(key, fingerprint);
            if (earlier.isPresent()) {
                return earlier.get();
            }
            claimed = store.tryStart(key, fingerprint);
            if (!claimed) {
                // lost the race to a parallel request between find() and tryStart()
                return replay(key, fingerprint).orElseThrow(IdempotencyInProgressException::new);
            }
        } catch (DataAccessException redisDown) {
            log.warn("Idempotency store unavailable ({}); relying on the database unique key", redisDown.getMessage());
            return createInDatabase(key, command, createdBy);
        }
        try {
            OrderService.CreateResult result = createInDatabase(key, command, createdBy);
            completeQuietly(key, fingerprint, result);
            return result;
        } catch (RuntimeException e) {
            releaseQuietly(key);
            throw e;
        }
    }

    private Optional<OrderService.CreateResult> replay(String key, String fingerprint) {
        Optional<IdempotencyStore.Entry> entry = store.find(key);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (!entry.get().fingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
        if (!entry.get().done()) {
            throw new IdempotencyInProgressException();
        }
        log.info("Replaying Idempotency-Key {} -> order {}", key, entry.get().orderId());
        return Optional.of(new OrderService.CreateResult(orderService.get(entry.get().orderId()), true));
    }

    private OrderService.CreateResult createInDatabase(String key, CreateOrderCommand command, String createdBy) {
        try {
            return orderService.create(command, key, createdBy);
        } catch (DataIntegrityViolationException duplicate) {
            // two requests passed the key check at the same time; the loser now finds the winner's order
            log.info("Concurrent create with Idempotency-Key {}, returning the committed order", key);
            return orderService.create(command, key, createdBy);
        }
    }

    private void completeQuietly(String key, String fingerprint, OrderService.CreateResult result) {
        try {
            store.complete(key, fingerprint, result.order().getOrderId());
        } catch (DataAccessException e) {
            log.warn("Could not mark Idempotency-Key {} done ({}); the database key still protects retries", key,
                    e.getMessage());
        }
    }

    private void releaseQuietly(String key) {
        try {
            store.release(key);
        } catch (DataAccessException e) {
            log.warn("Could not release Idempotency-Key {} ({}); it expires with its TTL", key, e.getMessage());
        }
    }
}
