package com.retail.oms.pricing.domain;

public enum PromotionKind {
    /** value = percent (0-100] */
    PERCENT,
    /** value = amount in the order currency (per unit for SKU promotions, per order for ORDER promotions) */
    FIXED
}
