package com.retail.oms.pricing.domain;

import java.math.BigDecimal;

/** What the rest of the service (and the Redis cache) knows about a SKU's price. */
public record PriceInfo(String sku, String currency, BigDecimal basePrice, String taxCode) {
}
