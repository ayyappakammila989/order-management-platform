package com.retail.oms.pricing.domain;

import java.math.BigDecimal;

/** A live promotion as the calculator sees it (also what is cached in Redis under promo:active). */
public record PromotionRule(long id, String name, String couponCode, PromotionScope scope, String sku,
                            PromotionKind kind, BigDecimal value, BigDecimal minSubtotal) {
}
