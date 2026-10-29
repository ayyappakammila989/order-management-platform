package com.retail.oms.pricing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retail.oms.common.web.OmsException;
import com.retail.oms.pricing.application.PriceCalculator.Input;
import com.retail.oms.pricing.domain.PromotionKind;
import com.retail.oms.pricing.domain.PromotionRule;
import com.retail.oms.pricing.domain.PromotionScope;
import com.retail.oms.pricing.domain.Quote;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PriceCalculatorTest {

    static final BigDecimal SHIPPING = bd("4.99");
    static final BigDecimal FREE_FROM = bd("75.00");
    static final BigDecimal NY = bd("0.08875");

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    static PromotionRule skuPromo(String sku, PromotionKind kind, String value) {
        return new PromotionRule(1, "promo " + sku, null, PromotionScope.SKU, sku, kind, bd(value), bd("0"));
    }

    static PromotionRule coupon(String code, PromotionKind kind, String value, String min) {
        return new PromotionRule(2, "coupon " + code, code, PromotionScope.ORDER, null, kind, bd(value), bd(min));
    }

    static Quote calc(List<Input> inputs, List<PromotionRule> skuPromos, PromotionRule coupon) {
        return PriceCalculator.calculate("USD", inputs, skuPromos, coupon, SHIPPING, FREE_FROM);
    }

    @Test
    void plainLineGetsTaxAndShipping() {
        Quote q = calc(List.of(new Input("SKU-1", 2, bd("19.99"), NY)), List.of(), null);

        assertThat(q.subtotal()).isEqualByComparingTo("39.98");
        assertThat(q.discountTotal()).isEqualByComparingTo("0.00");
        assertThat(q.taxTotal()).isEqualByComparingTo("3.55");        // 39.98 x 0.08875 = 3.548 -> 3.55
        assertThat(q.shippingTotal()).isEqualByComparingTo("4.99");
        assertThat(q.grandTotal()).isEqualByComparingTo("48.52");
        assertThat(q.lines().get(0).unitPrice()).isEqualByComparingTo("19.99");
    }

    @Test
    void percentPromotionRoundsHalfUpAndTaxIsOnTheDiscountedAmount() {
        Quote q = calc(List.of(new Input("SKU-2", 1, bd("49.50"), BigDecimal.ZERO)),
                List.of(skuPromo("SKU-2", PromotionKind.PERCENT, "15")), null);

        assertThat(q.discountTotal()).isEqualByComparingTo("7.43");    // 7.425 -> 7.43
        assertThat(q.grandTotal()).isEqualByComparingTo("47.06");      // 49.50 - 7.43 + 0 + 4.99
        assertThat(q.appliedPromotions()).hasSize(1);
    }

    @Test
    void fixedSkuPromotionIsPerUnitAndNeverMoreThanTheLine() {
        Quote q = calc(List.of(new Input("SKU-3", 3, bd("5.00"), BigDecimal.ZERO),
                new Input("SKU-5", 2, bd("0.99"), BigDecimal.ZERO)),
                List.of(skuPromo("SKU-3", PromotionKind.FIXED, "1.00"), skuPromo("SKU-5", PromotionKind.FIXED, "5.00")),
                null);

        assertThat(q.lines().get(0).discount()).isEqualByComparingTo("3.00");   // 1.00 x 3 units
        assertThat(q.lines().get(1).discount()).isEqualByComparingTo("1.98");    // capped at the line (0.99 x 2)
    }

    @Test
    void bestOfSeveralPromotionsForOneSkuWins() {
        Quote q = calc(List.of(new Input("SKU-4", 1, bd("100.00"), BigDecimal.ZERO)),
                List.of(skuPromo("SKU-4", PromotionKind.PERCENT, "10"), skuPromo("SKU-4", PromotionKind.FIXED, "25.00")),
                null);

        assertThat(q.discountTotal()).isEqualByComparingTo("25.00");
        assertThat(q.appliedPromotions()).hasSize(1);
    }

    @Test
    void couponIsSpreadOverLinesInWholeCentsAndAddsUpExactly() {
        Quote q = calc(List.of(new Input("SKU-1", 1, bd("19.99"), BigDecimal.ZERO),
                new Input("SKU-4", 1, bd("129.00"), BigDecimal.ZERO),
                new Input("SKU-5", 3, bd("0.99"), BigDecimal.ZERO)),
                List.of(), coupon("SPRING10", PromotionKind.PERCENT, "10", "50"));

        // 10% of 151.96 = 15.196 -> 15.20, split 2.00 / 12.90 / 0.30 by largest remainder (hand-checked)
        assertThat(q.discountTotal()).isEqualByComparingTo("15.20");
        assertThat(q.lines()).extracting(Quote.QuoteLine::discount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(bd("2.00"), bd("12.90"), bd("0.30"));
        assertThat(q.shippingTotal()).isEqualByComparingTo("0.00");   // 136.76 after discount >= 75
        assertThat(q.appliedPromotions()).extracting(Quote.AppliedPromotion::couponCode).containsExactly("SPRING10");
    }

    @Test
    void couponBelowMinimumIsRefused() {
        assertThatThrownBy(() -> calc(List.of(new Input("SKU-1", 1, bd("19.99"), BigDecimal.ZERO)), List.of(),
                coupon("SPRING10", PromotionKind.PERCENT, "10", "50")))
                .isInstanceOf(OmsException.class)
                .hasMessageContaining("SPRING10")
                .extracting(e -> ((OmsException) e).getErrorCode()).isEqualTo("PRC-422");
    }

    @Test
    void fixedCouponCannotMakeTheOrderNegative() {
        Quote q = calc(List.of(new Input("SKU-5", 1, bd("0.99"), BigDecimal.ZERO)), List.of(),
                coupon("BIG", PromotionKind.FIXED, "5.00", "0"));

        assertThat(q.discountTotal()).isEqualByComparingTo("0.99");
        assertThat(q.grandTotal()).isEqualByComparingTo("4.99");       // only shipping is left
    }

    @Test
    void couponAfterSkuPromotionAppliesToTheDiscountedAmount() {
        Quote q = calc(List.of(new Input("SKU-4", 1, bd("100.00"), BigDecimal.ZERO)),
                List.of(skuPromo("SKU-4", PromotionKind.PERCENT, "50")),
                coupon("TEN", PromotionKind.PERCENT, "10", "0"));

        assertThat(q.discountTotal()).isEqualByComparingTo("55.00");   // 50 off, then 10% of the remaining 50
    }

    @Test
    void freeShippingStartsExactlyAtTheThreshold() {
        assertThat(calc(List.of(new Input("A", 1, bd("75.00"), BigDecimal.ZERO)), List.of(), null).shippingTotal())
                .isEqualByComparingTo("0.00");
        assertThat(calc(List.of(new Input("A", 1, bd("74.99"), BigDecimal.ZERO)), List.of(), null).shippingTotal())
                .isEqualByComparingTo("4.99");
    }

    @Test
    void allocationGivesEveryCentAwayWithNoNegativeParts() {
        BigDecimal[] parts = PriceCalculator.allocate(bd("0.01"), new BigDecimal[] {bd("1.00"), bd("1.00"), bd("1.00")});
        assertThat(parts).extracting(BigDecimal::toPlainString).containsExactly("0.01", "0.00", "0.00");
    }

    /** Seeded random carts: the money identities must hold for all of them, not just the hand-picked ones. */
    @Test
    void moneyIdentitiesHoldForRandomCarts() {
        Random random = new Random(42);
        for (int run = 0; run < 3000; run++) {
            int n = 1 + random.nextInt(6);
            List<Input> inputs = new ArrayList<>();
            List<PromotionRule> promos = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String sku = "S" + i;
                inputs.add(new Input(sku, 1 + random.nextInt(9), BigDecimal.valueOf(1 + random.nextInt(30000), 2),
                        BigDecimal.valueOf(random.nextInt(11000), 5)));
                if (random.nextBoolean()) {
                    promos.add(random.nextBoolean()
                            ? skuPromo(sku, PromotionKind.PERCENT, String.valueOf(1 + random.nextInt(100)))
                            : skuPromo(sku, PromotionKind.FIXED, BigDecimal.valueOf(1 + random.nextInt(5000), 2).toPlainString()));
                }
            }
            PromotionRule c = random.nextInt(3) == 0 ? null : random.nextBoolean()
                    ? coupon("C", PromotionKind.PERCENT, String.valueOf(1 + random.nextInt(100)), "0")
                    : coupon("C", PromotionKind.FIXED, BigDecimal.valueOf(1 + random.nextInt(100000), 2).toPlainString(), "0");

            Quote q = calc(inputs, promos, c);

            BigDecimal discountSum = BigDecimal.ZERO;
            BigDecimal taxSum = BigDecimal.ZERO;
            BigDecimal grandFromLines = BigDecimal.ZERO;
            for (Quote.QuoteLine l : q.lines()) {
                BigDecimal gross = l.unitPrice().multiply(BigDecimal.valueOf(l.quantity()));
                assertThat(l.discount()).as("run %d line discount", run).isBetween(BigDecimal.ZERO, gross);
                assertThat(l.tax()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                discountSum = discountSum.add(l.discount());
                taxSum = taxSum.add(l.tax());
                grandFromLines = grandFromLines.add(gross).subtract(l.discount()).add(l.tax());
            }
            assertThat(discountSum).as("run %d discount total", run).isEqualByComparingTo(q.discountTotal());
            assertThat(taxSum).isEqualByComparingTo(q.taxTotal());
            assertThat(grandFromLines.add(q.shippingTotal())).as("run %d grand total", run)
                    .isEqualByComparingTo(q.grandTotal());
            assertThat(q.grandTotal().scale()).isEqualTo(2);
        }
    }
}
