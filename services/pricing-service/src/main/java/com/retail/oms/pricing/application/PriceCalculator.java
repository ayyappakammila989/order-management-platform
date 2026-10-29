package com.retail.oms.pricing.application;

import com.retail.oms.common.web.OmsException;
import com.retail.oms.pricing.domain.PromotionKind;
import com.retail.oms.pricing.domain.PromotionRule;
import com.retail.oms.pricing.domain.Quote;
import com.retail.oms.pricing.domain.Quote.AppliedPromotion;
import com.retail.oms.pricing.domain.Quote.QuoteLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * All the money arithmetic, with no I/O so it can be tested exhaustively.
 * Rules: (1) per line, the best automatic SKU promotion; (2) one coupon on what is left after (1), spread over the
 * lines in whole cents (largest remainder) so the pieces add up exactly; (3) tax = rate x (line - discounts), rounded
 * half-up per line; (4) flat shipping unless the discounted subtotal reaches the free-shipping threshold.
 */
public final class PriceCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record Input(String sku, int quantity, BigDecimal unitPrice, BigDecimal taxRate) {
    }

    private PriceCalculator() {
    }

    public static Quote calculate(String currency, List<Input> inputs, List<PromotionRule> skuPromotions,
            PromotionRule coupon, BigDecimal shippingFlat, BigDecimal freeShippingFrom) {
        int n = inputs.size();
        BigDecimal[] gross = new BigDecimal[n];
        BigDecimal[] lineDiscount = new BigDecimal[n];
        List<AppliedPromotion> applied = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (int i = 0; i < n; i++) {
            Input in = inputs.get(i);
            gross[i] = money(in.unitPrice().multiply(BigDecimal.valueOf(in.quantity())));
            subtotal = subtotal.add(gross[i]);
            lineDiscount[i] = BigDecimal.ZERO.setScale(2);
            PromotionRule best = null;
            BigDecimal bestAmount = BigDecimal.ZERO.setScale(2);
            for (PromotionRule promo : skuPromotions) {
                if (!in.sku().equals(promo.sku())) {
                    continue;
                }
                BigDecimal amount = skuDiscount(promo, in.unitPrice(), in.quantity(), gross[i]);
                if (amount.compareTo(bestAmount) > 0) {
                    best = promo;
                    bestAmount = amount;
                }
            }
            if (best != null) {
                lineDiscount[i] = bestAmount;
                applied.add(new AppliedPromotion(best.name(), null, bestAmount));
            }
        }

        BigDecimal[] net = new BigDecimal[n];
        BigDecimal netTotal = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            net[i] = gross[i].subtract(lineDiscount[i]);
            netTotal = netTotal.add(net[i]);
        }

        BigDecimal[] couponShare = new BigDecimal[n];
        java.util.Arrays.fill(couponShare, BigDecimal.ZERO.setScale(2));
        if (coupon != null) {
            if (netTotal.compareTo(coupon.minSubtotal()) < 0) {
                throw new OmsException("PRC-422", HttpStatus.UNPROCESSABLE_ENTITY,
                        "Coupon " + coupon.couponCode() + " needs at least " + coupon.minSubtotal() + " in the cart");
            }
            BigDecimal couponTotal = couponDiscount(coupon, netTotal);
            couponShare = allocate(couponTotal, net);
            applied.add(new AppliedPromotion(coupon.name(), coupon.couponCode(), couponTotal));
        }

        List<QuoteLine> lines = new ArrayList<>(n);
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            Input in = inputs.get(i);
            BigDecimal discount = lineDiscount[i].add(couponShare[i]);
            BigDecimal taxable = gross[i].subtract(discount);
            BigDecimal tax = money(taxable.multiply(in.taxRate()));
            discountTotal = discountTotal.add(discount);
            taxTotal = taxTotal.add(tax);
            lines.add(new QuoteLine(in.sku(), in.quantity(), in.unitPrice(), discount, tax, in.taxRate()));
        }

        BigDecimal afterDiscount = subtotal.subtract(discountTotal);
        BigDecimal shipping = afterDiscount.compareTo(freeShippingFrom) >= 0
                ? BigDecimal.ZERO.setScale(2) : money(shippingFlat);
        BigDecimal grand = afterDiscount.add(taxTotal).add(shipping);
        return new Quote(currency, lines, subtotal, discountTotal, taxTotal, shipping, grand, applied);
    }

    /** Discount a coupon gives on this amount (never more than the amount itself). */
    public static BigDecimal couponDiscount(PromotionRule coupon, BigDecimal amount) {
        BigDecimal discount = coupon.kind() == PromotionKind.PERCENT
                ? money(amount.multiply(coupon.value()).divide(HUNDRED, 4, RoundingMode.HALF_UP))
                : money(coupon.value());
        return discount.min(amount);
    }

    private static BigDecimal skuDiscount(PromotionRule promo, BigDecimal unitPrice, int quantity, BigDecimal gross) {
        BigDecimal discount = promo.kind() == PromotionKind.PERCENT
                ? money(gross.multiply(promo.value()).divide(HUNDRED, 4, RoundingMode.HALF_UP))
                : money(promo.value().multiply(BigDecimal.valueOf(quantity)));
        return discount.min(gross);
    }

    /** Splits {@code total} over {@code weights} in whole cents; the parts always add up to {@code total}. */
    static BigDecimal[] allocate(BigDecimal total, BigDecimal[] weights) {
        int n = weights.length;
        BigDecimal[] result = new BigDecimal[n];
        long totalCents = total.movePointRight(2).longValueExact();
        long weightSum = 0;
        for (BigDecimal w : weights) {
            weightSum += w.movePointRight(2).longValueExact();
        }
        long[] cents = new long[n];
        BigDecimal[] fraction = new BigDecimal[n];
        long assigned = 0;
        for (int i = 0; i < n; i++) {
            if (weightSum == 0) {
                fraction[i] = BigDecimal.ZERO;
                continue;
            }
            BigDecimal exact = BigDecimal.valueOf(totalCents).multiply(weights[i].movePointRight(2))
                    .divide(BigDecimal.valueOf(weightSum), 10, RoundingMode.DOWN);
            cents[i] = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            fraction[i] = exact.subtract(BigDecimal.valueOf(cents[i]));
            assigned += cents[i];
        }
        long left = weightSum == 0 ? 0 : totalCents - assigned;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing((Integer i) -> fraction[i]).reversed());
        for (int k = 0; k < left; k++) {
            cents[order.get(k)]++;
        }
        for (int i = 0; i < n; i++) {
            result[i] = BigDecimal.valueOf(cents[i], 2);
        }
        return result;
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
