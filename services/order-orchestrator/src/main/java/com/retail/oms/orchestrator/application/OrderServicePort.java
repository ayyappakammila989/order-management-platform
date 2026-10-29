package com.retail.oms.orchestrator.application;

import java.util.UUID;

/** order-service's PATCH /internal/v1/orders/{orderId}/status (architecture 2, "used by saga"). */
public interface OrderServicePort {

    /**
     * Sets the order status; the same status again is a no-op on the other side, so this is safe to retry.
     *
     * @throws OrderRejectedException  order-service refused (e.g. ORD-409: the order is already cancelled)
     * @throws OrderServiceUnavailableException order-service or Keycloak not reachable: retry later
     */
    void changeStatus(UUID orderId, String status, String reason);
}
