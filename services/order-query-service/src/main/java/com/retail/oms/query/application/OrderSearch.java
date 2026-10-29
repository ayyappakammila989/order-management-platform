package com.retail.oms.query.application;

import java.util.List;

/** What a caller may filter on. {@code q} matches an order number (exact or prefix), an order id or a SKU. */
public record OrderSearch(String q, String status, String customerId, int page, int size) {

    public record Page(List<OrderDocument> items, long total, int page, int size) {
    }
}
