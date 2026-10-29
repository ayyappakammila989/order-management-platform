package com.retail.oms.order.mapper;

import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import com.retail.oms.order.domain.AddressType;
import com.retail.oms.order.domain.Order;
import com.retail.oms.order.domain.OrderAddress;
import com.retail.oms.order.domain.OrderLine;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Order -> Avro events. Hand-written rather than MapStruct: the Avro builders are generated code with
 * logical-type conversions, and an explicit mapping keeps the event contract obvious to a reviewer.
 */
@Component
public class OrderEventMapper {

    public OrderCreatedEvent toCreatedEvent(UUID eventId, Order order) {
        return OrderCreatedEvent.newBuilder()
                .setEventId(eventId.toString())
                .setOccurredAt(millis(order.getCreatedAt()))
                .setOrderId(order.getOrderId().toString())
                .setOrderNumber(order.getOrderNumber())
                .setCustomerId(order.getCustomerId())
                .setChannel(order.getChannel().name())
                .setStatus(order.getStatus().name())
                .setCurrency(order.getCurrency())
                .setSubtotal(order.getSubtotal())
                .setDiscountTotal(order.getDiscountTotal())
                .setTaxTotal(order.getTaxTotal())
                .setShippingTotal(order.getShippingTotal())
                .setGrandTotal(order.getGrandTotal())
                .setLines(order.getLines().stream().map(OrderEventMapper::toLine).toList())
                .setShipToZip(order.address(AddressType.SHIP).map(OrderAddress::getZip).orElse(null))
                .build();
    }

    public OrderStatusChangedEvent toStatusChangedEvent(UUID eventId, Order order, Order.StatusChange change) {
        return OrderStatusChangedEvent.newBuilder()
                .setEventId(eventId.toString())
                .setOccurredAt(millis(change.at()))
                .setOrderId(order.getOrderId().toString())
                .setOrderNumber(order.getOrderNumber())
                .setCustomerId(order.getCustomerId())
                .setFromStatus(change.from().name())
                .setToStatus(change.to().name())
                .setReason(change.reason())
                .setChangedBy(change.changedBy())
                .build();
    }

    private static com.retail.oms.events.order.OrderLine toLine(OrderLine line) {
        return com.retail.oms.events.order.OrderLine.newBuilder()
                .setLineId(line.getLineId())
                .setSku(line.getSku())
                .setQuantity(line.getQuantity())
                .setUnitPrice(line.getUnitPrice())
                .setDiscount(line.getDiscount())
                .setTax(line.getTax())
                .setFulfillmentType(line.getFulfillmentType().name())
                .build();
    }

    /** Avro timestamp-millis: drop sub-millisecond precision so the value survives a round trip unchanged. */
    private static Instant millis(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }
}
