package com.retail.oms.query.application;

import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import java.util.Optional;

/**
 * The orders read model. Both write methods are safe to repeat and safe in any order: created and status events
 * travel on different topics, so a cancellation can be applied before the order it cancels.
 */
public interface OrderIndex {

    void applyCreated(OrderCreatedEvent event);

    void applyStatusChanged(OrderStatusChangedEvent event);

    Optional<OrderDocument> find(String orderId);

    OrderSearch.Page search(OrderSearch search);
}
