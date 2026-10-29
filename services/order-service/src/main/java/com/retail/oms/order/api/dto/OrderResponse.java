package com.retail.oms.order.api.dto;

import com.retail.oms.order.domain.Channel;
import com.retail.oms.order.domain.FulfillmentType;
import com.retail.oms.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String orderNumber,
        String customerId,
        Channel channel,
        OrderStatus status,
        String currency,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal shippingTotal,
        BigDecimal grandTotal,
        List<Line> lines,
        Address shippingAddress,
        Address billingAddress,
        Instant createdAt,
        Instant updatedAt) {

    public record Line(Long lineId, String sku, int quantity, BigDecimal unitPrice, BigDecimal discount,
            BigDecimal tax, String lineStatus, FulfillmentType fulfillmentType) {
    }

    public record Address(String name, String line1, String city, String state, String zip) {

        @Override
        public String toString() {
            return "Address[zip=" + zip + "]";
        }
    }
}
