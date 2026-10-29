package com.retail.oms.query.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/** One document of the orders index: the whole order denormalised for reading (architecture 4.3). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderDocument(String orderId, String orderNumber, String status, String channel, String currency,
                            String shipToZip, Customer customer, Totals totals, List<Line> lines,
                            List<Shipment> shipments, List<StatusEntry> statusHistory, String createdAt,
                            String updatedAt) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(String id, String email, String phone) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Totals(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal shippingTotal,
                         BigDecimal grandTotal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(Long lineId, String sku, String title, int quantity, BigDecimal unitPrice, BigDecimal discount,
                       BigDecimal tax, String fulfillmentType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Shipment(String trackingNumber, String carrier, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusEntry(String eventId, String from, String to, String at, String reason, String changedBy) {
    }
}
