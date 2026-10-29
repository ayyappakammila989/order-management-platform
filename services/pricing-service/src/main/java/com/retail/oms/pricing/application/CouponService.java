package com.retail.oms.pricing.application;

import com.retail.oms.pricing.domain.PromotionRule;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final PromotionCatalog promotions;

    public record Result(boolean valid, String reason, String name, BigDecimal discount) {
    }

    public Result validate(String code, BigDecimal subtotal) {
        Optional<PromotionRule> coupon = promotions.coupon(code);
        if (coupon.isEmpty()) {
            return new Result(false, "Coupon is not valid or has expired", null, null);
        }
        PromotionRule rule = coupon.get();
        if (subtotal.compareTo(rule.minSubtotal()) < 0) {
            return new Result(false, "Needs at least " + rule.minSubtotal() + " in the cart", rule.name(), null);
        }
        return new Result(true, null, rule.name(), PriceCalculator.couponDiscount(rule, subtotal));
    }
}
