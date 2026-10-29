package com.retail.oms.pricing.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("oms.pricing")
public record PricingProperties(
        BigDecimal shippingFlat,
        BigDecimal freeShippingFrom,
        Duration priceCacheTtl,
        Duration promoCacheTtl,
        Duration taxCacheTtl,
        TaxBreaker taxBreaker) {

    public record TaxBreaker(int slidingWindowSize, int minimumCalls, float failureRateThreshold, Duration openWait) {
    }
}
