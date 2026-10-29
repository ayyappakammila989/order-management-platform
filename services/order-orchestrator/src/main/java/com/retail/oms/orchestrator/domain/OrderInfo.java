package com.retail.oms.orchestrator.domain;

import java.util.List;

/** What the saga needs to know about the order, copied from oms.order.created. */
public record OrderInfo(String orderId, String orderNumber, String customerId, String shipToZip, String currency,
                        String grandTotal, List<Line> lines) {

    public record Line(String sku, int quantity) {
    }
}
