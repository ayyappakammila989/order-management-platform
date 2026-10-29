package com.retail.oms.pricing.application;

import com.retail.oms.common.web.OmsException;
import com.retail.oms.pricing.config.PricingProperties;
import com.retail.oms.pricing.domain.PriceInfo;
import com.retail.oms.pricing.domain.PromotionRule;
import com.retail.oms.pricing.domain.Quote;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QuoteService {

    private final PriceCatalog prices;
    private final PromotionCatalog promotions;
    private final TaxRateService taxRates;
    private final PricingProperties props;

    public record Line(String sku, int quantity) {
    }

    public Quote quote(String currency, String shipToZip, String couponCode, List<Line> lines) {
        String zip5 = shipToZip.substring(0, 5);
        Map<String, BigDecimal> rateByTaxCode = new HashMap<>();
        List<PriceCalculator.Input> inputs = new ArrayList<>(lines.size());
        for (Line line : lines) {
            PriceInfo price = prices.priceOf(line.sku());
            if (!price.currency().equals(currency)) {
                throw new OmsException("PRC-422", HttpStatus.UNPROCESSABLE_ENTITY,
                        "SKU " + line.sku() + " is priced in " + price.currency() + ", not " + currency);
            }
            BigDecimal rate = rateByTaxCode.computeIfAbsent(price.taxCode(), code -> taxRates.rateFor(zip5, code));
            inputs.add(new PriceCalculator.Input(line.sku(), line.quantity(), price.basePrice(), rate));
        }
        PromotionRule coupon = null;
        if (couponCode != null && !couponCode.isBlank()) {
            coupon = promotions.coupon(couponCode).orElseThrow(() -> new OmsException("PRC-422",
                    HttpStatus.UNPROCESSABLE_ENTITY, "Coupon " + couponCode.trim() + " is not valid"));
        }
        return PriceCalculator.calculate(currency, inputs, promotions.automaticSkuPromotions(), coupon,
                props.shippingFlat(), props.freeShippingFrom());
    }
}
