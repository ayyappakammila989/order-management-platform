package com.retail.oms.orchestrator.infrastructure.messaging;

import com.retail.oms.events.EventHeaders;
import com.retail.oms.events.Topics;
import com.retail.oms.events.inventory.StockReleasedEvent;
import com.retail.oms.events.inventory.StockReserveFailedEvent;
import com.retail.oms.events.inventory.StockReservedEvent;
import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import com.retail.oms.kafka.AvroEventDecoder;
import com.retail.oms.orchestrator.application.InvalidMessageException;
import com.retail.oms.orchestrator.application.engine.SagaEngine;
import com.retail.oms.orchestrator.application.steps.OrderPlacementSaga;
import com.retail.oms.orchestrator.application.steps.ReserveStockStep;
import com.retail.oms.orchestrator.domain.OrderInfo;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns Kafka messages into saga engine calls: oms.order.created starts a saga, inventory replies move it on,
 * oms.order.cancelled aborts it. Retry with backoff, then oms.order-orchestrator.dlq (libs/oms-common-kafka).
 */
@Component
@Slf4j
public class SagaMessageListener {

    private final AvroEventDecoder decoder;
    private final SagaEngine engine;

    public SagaMessageListener(AvroEventDecoder decoder, SagaEngine engine) {
        this.decoder = decoder;
        this.engine = engine;
    }

    @KafkaListener(id = "saga-messages", idIsGroup = false, topics = {Topics.ORDER_CREATED, Topics.ORDER_CANCELLED,
            Topics.INVENTORY_RESERVED, Topics.INVENTORY_RESERVE_FAILED, Topics.INVENTORY_RELEASED})
    public void onMessage(ConsumerRecord<String, byte[]> record) {
        String correlationId = header(record, EventHeaders.CORRELATION_ID);
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            Object message = decoder.decode(record.topic(), record.headers(), record.value());
            switch (message) {
                case OrderCreatedEvent e -> engine.start(OrderPlacementSaga.TYPE, orderInfo(e), e.getEventId(), correlationId);
                case OrderStatusChangedEvent e when "CANCELLED".equals(e.getToStatus()) ->
                        engine.abort(uuid(e.getOrderId()), "Order cancelled by " + e.getChangedBy()
                                + (e.getReason() == null ? "" : ": " + e.getReason()), true, e.getEventId(), correlationId);
                case StockReservedEvent e -> engine.stepSucceeded(uuid(e.getSagaId()), ReserveStockStep.NAME,
                        e.getCommandId(), data -> data.withResult(ReserveStockStep.RESERVATION_ID, e.getReservationId()),
                        e.getEventId(), correlationId);
                case StockReserveFailedEvent e -> engine.stepFailed(uuid(e.getSagaId()), ReserveStockStep.NAME,
                        e.getCommandId(), e.getReason(), e.getEventId(), correlationId);
                case StockReleasedEvent e -> onReleased(e, correlationId);
                default -> throw new InvalidMessageException("Unexpected " + message.getClass().getSimpleName()
                        + " on " + record.topic());
            }
        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * A reply to our release command moves the compensation on. A release nobody asked for: EXPIRED means the stock
     * is gone while the saga may still need it, so a running saga is aborted; ORDER_CANCELLED etc. need nothing.
     */
    private void onReleased(StockReleasedEvent e, String correlationId) {
        if (e.getCommandId() != null && e.getSagaId() != null) {
            engine.compensated(uuid(e.getSagaId()), ReserveStockStep.NAME, e.getCommandId(), e.getReason(),
                    e.getEventId(), correlationId);
        } else if ("EXPIRED".equals(e.getReason())) {
            engine.abort(uuid(e.getOrderId()), "Stock reservation expired", false, e.getEventId(), correlationId);
        } else {
            log.debug("Release of order {} ({}) needs no saga action", e.getOrderId(), e.getReason());
        }
    }

    static OrderInfo orderInfo(OrderCreatedEvent e) {
        return new OrderInfo(e.getOrderId(), e.getOrderNumber(), e.getCustomerId(), e.getShipToZip(), e.getCurrency(),
                e.getGrandTotal().toPlainString(),
                e.getLines().stream().map(l -> new OrderInfo.Line(l.getSku(), l.getQuantity())).toList());
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidMessageException("Not a UUID: " + value);
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
