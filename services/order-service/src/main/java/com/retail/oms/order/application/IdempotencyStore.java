package com.retail.oms.order.application;

import java.util.Optional;
import java.util.UUID;

/** Remembers which Idempotency-Key produced which order (Redis in production, a map in unit tests). */
public interface IdempotencyStore {

    /** IN_PROGRESS while the first request runs, then DONE with the order id. */
    record Entry(boolean done, String fingerprint, UUID orderId) {
    }

    Optional<Entry> find(String key);

    /** Atomically claims the key; false when another request already holds it. */
    boolean tryStart(String key, String fingerprint);

    void complete(String key, String fingerprint, UUID orderId);

    /** Frees the key after a failure, so the client can retry with the same key. */
    void release(String key);
}
