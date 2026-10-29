package com.retail.oms.pricing.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.retail.oms.common.web.OmsException;
import com.retail.oms.pricing.config.PricingProperties;
import com.retail.oms.pricing.config.TaxBreakerConfig;
import com.retail.oms.pricing.infrastructure.cache.PricingCache;
import com.retail.oms.pricing.infrastructure.tax.TaxClient;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Tax rate for a ZIP and tax code. Redis first (24 h, architecture 8), then tax-service behind a circuit breaker.
 * Because the cache is read first, a tax outage only hurts ZIPs that have not been seen in the last 24 hours.
 * We never guess a rate: with no cached value and no answer, the quote fails with 503.
 */
@Service
@Slf4j
public class TaxRateService {

    private final TaxClient client;
    private final PricingCache cache;
    private final PricingProperties props;
    private final CircuitBreaker breaker;

    public TaxRateService(TaxClient client, PricingCache cache, PricingProperties props,
            CircuitBreakerFactory<?, ?> breakerFactory) {
        this.client = client;
        this.cache = cache;
        this.props = props;
        this.breaker = breakerFactory.create(TaxBreakerConfig.TAX_BREAKER);
    }

    public BigDecimal rateFor(String zip5, String taxCode) {
        String key = "tax:" + zip5 + ":" + taxCode;
        return cache.get(key, new TypeReference<BigDecimal>() { }).orElseGet(() -> {
            BigDecimal rate = breaker.run(
                    () -> client.rate(new TaxClient.TaxRateRequest(zip5, taxCode)).rate(),
                    this::unavailable);
            cache.put(key, rate, props.taxCacheTtl());
            return rate;
        });
    }

    private BigDecimal unavailable(Throwable cause) {
        log.warn("tax-service unavailable: {}", cause.toString());
        throw new OmsException("PRC-503", HttpStatus.SERVICE_UNAVAILABLE,
                "Tax cannot be calculated right now, please try again shortly");
    }
}
