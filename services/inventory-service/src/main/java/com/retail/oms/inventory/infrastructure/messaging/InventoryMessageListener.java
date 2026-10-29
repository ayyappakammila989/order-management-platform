package com.retail.oms.inventory.infrastructure.messaging;

import com.retail.oms.events.EventHeaders;
import com.retail.oms.events.Topics;
import com.retail.oms.events.inventory.ReleaseStockCommand;
import com.retail.oms.events.inventory.ReserveStockCommand;
import com.retail.oms.events.inventory.StockSnapshotEvent;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import com.retail.oms.inventory.application.InvalidMessageException;
import com.retail.oms.inventory.application.ReservationService;
import com.retail.oms.inventory.application.ReserveRequest;
import com.retail.oms.inventory.application.SagaContext;
import com.retail.oms.inventory.application.SnapshotService;
import com.retail.oms.inventory.domain.ReleaseReason;
import com.retail.oms.kafka.AvroEventDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Everything inventory-service consumes: saga commands, order status events and stock snapshots. A failure is
 * retried with backoff and then dead-lettered (oms.inventory.dlq); each message is applied once
 * (processed_message in the same transaction as its effect).
 */
@Component
@Slf4j
public class InventoryMessageListener {

    private final AvroEventDecoder decoder;
    private final ReservationService reservations;
    private final SnapshotService snapshots;

    public InventoryMessageListener(AvroEventDecoder decoder, ReservationService reservations, SnapshotService snapshots) {
        this.decoder = decoder;
        this.reservations = reservations;
        this.snapshots = snapshots;
    }

    @KafkaListener(id = "inventory-messages", idIsGroup = false, topics = {Topics.INVENTORY_RESERVE_CMD,
            Topics.INVENTORY_RELEASE_CMD, Topics.ORDER_CANCELLED, Topics.ORDER_CONFIRMED, Topics.INVENTORY_SNAPSHOT})
    public void onMessage(ConsumerRecord<String, byte[]> record) {
        String correlationId = header(record, EventHeaders.CORRELATION_ID);
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            Object message = decoder.decode(record.topic(), record.headers(), record.value());
            switch (message) {
                case ReserveStockCommand cmd -> reservations.reserve(
                        new ReserveRequest(orderId(cmd.getOrderId()), cmd.getShipToZip(), cmd.getLines().stream()
                                .map(l -> new ReserveRequest.Line(l.getSku(), positive(l.getQuantity(), l.getSku()))).toList()),
                        new SagaContext(cmd.getCommandId(), cmd.getSagaId(), correlationId));
                case ReleaseStockCommand cmd -> reservations.releaseForOrder(orderId(cmd.getOrderId()),
                        ReleaseReason.COMPENSATION, new SagaContext(cmd.getCommandId(), cmd.getSagaId(), correlationId),
                        cmd.getCommandId());
                case OrderStatusChangedEvent e when "CANCELLED".equals(e.getToStatus()) -> reservations.releaseForOrder(
                        orderId(e.getOrderId()), ReleaseReason.ORDER_CANCELLED, SagaContext.none(correlationId), e.getEventId());
                case OrderStatusChangedEvent e when "CONFIRMED".equals(e.getToStatus()) ->
                        reservations.keepUntilShipment(orderId(e.getOrderId()), e.getEventId());
                case StockSnapshotEvent s -> snapshots.apply(s, correlationId);
                default -> throw new InvalidMessageException("Unexpected " + message.getClass().getSimpleName()
                        + " on " + record.topic());
            }
        } finally {
            MDC.remove("correlationId");
        }
    }

    private static UUID orderId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("orderId is not a UUID: " + value);
        }
    }

    private static int positive(int quantity, String sku) {
        if (quantity <= 0) {
            throw new InvalidMessageException("Quantity " + quantity + " for " + sku);
        }
        return quantity;
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
