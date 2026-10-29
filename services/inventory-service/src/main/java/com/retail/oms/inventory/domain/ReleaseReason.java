package com.retail.oms.inventory.domain;

public enum ReleaseReason {
    /** the saga failed after the reservation and sent release-cmd */
    COMPENSATION,
    /** oms.order.cancelled */
    ORDER_CANCELLED,
    /** the order was never confirmed within the reservation TTL */
    EXPIRED,
    /** DELETE /internal/v1/reservations/{id} */
    MANUAL
}
