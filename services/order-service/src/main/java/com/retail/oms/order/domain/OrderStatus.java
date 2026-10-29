package com.retail.oms.order.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order lifecycle. The saga moves PENDING to CONFIRMED (or CANCELLED on failure); fulfillment moves it on to
 * SHIPPED and DELIVERED. Cancelling is only possible before shipment.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> EnumSet.of(SHIPPED, CANCELLED);
            case SHIPPED -> EnumSet.of(DELIVERED);
            case DELIVERED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canMoveTo(OrderStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isCancellable() {
        return canMoveTo(CANCELLED);
    }
}
