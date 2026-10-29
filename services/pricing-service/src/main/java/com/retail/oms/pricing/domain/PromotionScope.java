package com.retail.oms.pricing.domain;

public enum PromotionScope {
    /** Discounts every unit of one SKU; applied automatically. */
    SKU,
    /** Discounts the whole order; needs a coupon code. */
    ORDER
}
