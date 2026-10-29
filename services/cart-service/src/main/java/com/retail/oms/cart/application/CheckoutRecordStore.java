package com.retail.oms.cart.application;

import com.retail.oms.cart.infrastructure.client.OrderClient;
import java.util.Optional;

/**
 * What a checkout attempt decided, keyed by customer + Idempotency-Key. It freezes the priced order request, so a
 * retry sends exactly the same body even if prices moved, and it remembers the order once created, so a retry after
 * the cart was deleted still gets the order back.
 */
public interface CheckoutRecordStore {

    record CheckoutRecord(String cartId, OrderClient.OrderRequest request, OrderClient.OrderSummary order) {
    }

    Optional<CheckoutRecord> find(String customerId, String idempotencyKey);

    /** @return false when another request already recorded this key */
    boolean saveRequest(String customerId, String idempotencyKey, String cartId, OrderClient.OrderRequest request);

    void saveOrder(String customerId, String idempotencyKey, CheckoutRecord record);
}
