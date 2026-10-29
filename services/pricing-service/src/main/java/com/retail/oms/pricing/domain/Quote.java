package com.retail.oms.pricing.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Final prices for a cart. Per line, {@code discount} and {@code tax} are totals for the whole line, exactly
 * what order-service expects, so grandTotal = sum(unitPrice * quantity - discount + tax) + shippingTotal.
 */
public record Quote(String currency, List<QuoteLine> lines, BigDecimal subtotal, BigDecimal discountTotal,
                    BigDecimal taxTotal, BigDecimal shippingTotal, BigDecimal grandTotal,
                    List<AppliedPromotion> appliedPromotions) {

    public record QuoteLine(String sku, int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal tax,
                            BigDecimal taxRate) {
    }

    public record AppliedPromotion(String name, String couponCode, BigDecimal discount) {
    }
}
