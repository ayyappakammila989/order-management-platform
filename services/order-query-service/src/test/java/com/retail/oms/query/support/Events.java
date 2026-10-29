package com.retail.oms.query.support;

import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderLine;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builders for the Avro events the tests send. */
public final class Events {

    public static final Instant T0 = Instant.parse("2026-10-29T10:00:00Z");

    private Events() {
    }

    public static OrderCreatedEvent created(String orderId, String customerId, Instant at) {
        return created(UUID.randomUUID().toString(), orderId, customerId, at);
    }

    public static OrderCreatedEvent created(String eventId, String orderId, String customerId, Instant at) {
        return OrderCreatedEvent.newBuilder()
                .setEventId(eventId).setOccurredAt(at).setOrderId(orderId)
                .setOrderNumber("OMS-20261029-" + String.format("%06d", Math.abs(orderId.hashCode()) % 1_000_000))
                .setCustomerId(customerId).setChannel("WEB").setStatus("PENDING").setCurrency("USD")
                .setSubtotal(new BigDecimal("39.98")).setDiscountTotal(new BigDecimal("2.00"))
                .setTaxTotal(new BigDecimal("3.00")).setShippingTotal(new BigDecimal("4.99"))
                .setGrandTotal(new BigDecimal("45.97"))
                .setLines(List.of(OrderLine.newBuilder().setLineId(1L).setSku("SKU-1").setQuantity(2)
                        .setUnitPrice(new BigDecimal("19.99")).setDiscount(new BigDecimal("2.00"))
                        .setTax(new BigDecimal("3.00")).setFulfillmentType("SHIP").build()))
                .setShipToZip("10001").build();
    }

    public static OrderStatusChangedEvent status(String eventId, String orderId, String customerId, String from,
            String to, Instant at) {
        return OrderStatusChangedEvent.newBuilder()
                .setEventId(eventId).setOccurredAt(at).setOrderId(orderId)
                .setOrderNumber("OMS-20261029-" + String.format("%06d", Math.abs(orderId.hashCode()) % 1_000_000))
                .setCustomerId(customerId).setFromStatus(from).setToStatus(to).setReason("test").setChangedBy("tester")
                .build();
    }
}
