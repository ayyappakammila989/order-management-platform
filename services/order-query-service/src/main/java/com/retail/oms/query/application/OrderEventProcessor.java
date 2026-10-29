package com.retail.oms.query.application;

import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Idempotent consumer: an event id that was fully handled before is skipped; otherwise the event is applied to the
 * index and only then remembered. A crash between the two means a re-delivery, which the index absorbs.
 */
@Service
@Slf4j
public class OrderEventProcessor {

    private static final Pattern STATUS = Pattern.compile("[A-Z][A-Z_]{1,29}");

    private final OrderIndex index;
    private final ProcessedEvents processed;
    private final Counter applied;
    private final Counter duplicates;

    public OrderEventProcessor(OrderIndex index, ProcessedEvents processed, MeterRegistry meters) {
        this.index = index;
        this.processed = processed;
        this.applied = meters.counter("oms.query.events.applied");
        this.duplicates = meters.counter("oms.query.events.duplicate");
    }

    public void process(Object event) {
        String eventId = validate(event);
        if (processed.contains(eventId)) {
            duplicates.increment();
            log.info("Event {} was already handled, skipping", eventId);
            return;
        }
        if (event instanceof OrderCreatedEvent created) {
            index.applyCreated(created);
        } else if (event instanceof OrderStatusChangedEvent changed) {
            index.applyStatusChanged(changed);
        }
        processed.markProcessed(eventId);
        applied.increment();
    }

    /** @return the event id; throws InvalidEventException for anything that can never be indexed */
    static String validate(Object event) {
        if (event instanceof OrderCreatedEvent e) {
            require(e.getEventId(), "eventId");
            require(e.getOrderId(), "orderId");
            require(e.getOrderNumber(), "orderNumber");
            require(e.getCustomerId(), "customerId");
            requireStatus(e.getStatus());
            return e.getEventId();
        }
        if (event instanceof OrderStatusChangedEvent e) {
            require(e.getEventId(), "eventId");
            require(e.getOrderId(), "orderId");
            require(e.getOrderNumber(), "orderNumber");
            require(e.getCustomerId(), "customerId");
            requireStatus(e.getToStatus());
            return e.getEventId();
        }
        throw new InvalidEventException("Unsupported event type: " + (event == null ? "null" : event.getClass().getName()));
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventException("Event field '" + field + "' is empty");
        }
    }

    /** Any upper-case status name is accepted, so a status added by order-service later does not break this consumer. */
    private static void requireStatus(String status) {
        if (status == null || !STATUS.matcher(status).matches()) {
            throw new InvalidEventException("Not a valid order status: '" + status + "'");
        }
    }
}
