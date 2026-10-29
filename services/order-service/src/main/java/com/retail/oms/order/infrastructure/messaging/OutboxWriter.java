package com.retail.oms.order.infrastructure.messaging;

import com.retail.oms.common.web.CorrelationId;
import com.retail.oms.events.Topics;
import com.retail.oms.order.domain.Order;
import com.retail.oms.order.mapper.OrderEventMapper;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.specific.SpecificRecord;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes domain events to the outbox table. MANDATORY propagation: calling it outside the transaction that
 * changes the order is a bug (the event could then exist without the change, or the other way round), so Spring
 * throws instead of quietly opening a new transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxWriter {

    static final String AGGREGATE_TYPE = "order";

    private final OutboxEventRepository outbox;
    private final OrderEventMapper mapper;
    private final AvroEventSerializer serializer;
    private final Clock clock;

    public void orderCreated(Order order) {
        UUID eventId = UUID.randomUUID();
        write(eventId, order, "order.created", Topics.ORDER_CREATED, mapper.toCreatedEvent(eventId, order));
    }

    public void statusChanged(Order order, Order.StatusChange change) {
        UUID eventId = UUID.randomUUID();
        String status = change.to().name().toLowerCase(Locale.ROOT);
        write(eventId, order, "order." + status, Topics.orderStatus(change.to().name()),
                mapper.toStatusChangedEvent(eventId, order, change));
    }

    /** eventType "order.created" is routed by Debezium to topic "oms.order.created"; both names must agree. */
    private void write(UUID eventId, Order order, String eventType, String topic, SpecificRecord event) {
        byte[] payload = serializer.serialize(topic, event);
        outbox.save(new OutboxEvent(eventId, AGGREGATE_TYPE, order.getOrderId().toString(), eventType, payload,
                MDC.get(CorrelationId.MDC_KEY), clock.instant()));
        log.info("Outbox event {} {} for order {}", eventType, eventId, order.getOrderNumber());
    }
}
