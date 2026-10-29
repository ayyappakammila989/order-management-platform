package com.retail.oms.pricing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.retail.oms.common.web.OmsException;
import com.retail.oms.pricing.config.PricingProperties;
import com.retail.oms.pricing.config.TaxBreakerConfig;
import com.retail.oms.pricing.infrastructure.cache.PricingCache;
import com.retail.oms.pricing.infrastructure.tax.TaxClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

/** The real Resilience4j breaker with a small window, a mocked Feign client and a mocked cache. */
class TaxRateServiceTest {

    TaxClient client = mock(TaxClient.class);
    PricingCache cache = mock(PricingCache.class);
    TaxRateService service;

    @BeforeEach
    void setUp() {
        PricingProperties props = new PricingProperties(new BigDecimal("4.99"), new BigDecimal("75.00"),
                Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofHours(24),
                new PricingProperties.TaxBreaker(4, 4, 50f, Duration.ofSeconds(30)));
        var factory = new Resilience4JCircuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(), (Resilience4jBulkheadProvider) null);
        new TaxBreakerConfig().taxBreakerCustomizer(props).customize(factory);
        when(cache.get(any(String.class), any(TypeReference.class))).thenReturn(Optional.empty());
        service = new TaxRateService(client, cache, props, factory);
    }

    @Test
    void rateFromTheProviderIsCachedFor24Hours() {
        when(client.rate(any())).thenReturn(new TaxClient.TaxRateResponse("10001", "NY", new BigDecimal("0.08875"), "x"));

        assertThat(service.rateFor("10001", "GENERAL")).isEqualByComparingTo("0.08875");
        verify(cache).put("tax:10001:GENERAL", new BigDecimal("0.08875"), Duration.ofHours(24));
    }

    @Test
    void cachedRateMeansNoCallToTheProvider() {
        when(cache.get(any(String.class), any(TypeReference.class))).thenReturn(Optional.of(new BigDecimal("0.0825")));

        assertThat(service.rateFor("73301", "GENERAL")).isEqualByComparingTo("0.0825");
        verify(client, times(0)).rate(any());
    }

    @Test
    void providerFailureWithoutCachedRateBecomes503NotAGuess() {
        when(client.rate(any())).thenThrow(new IllegalStateException("connection refused"));

        assertThatThrownBy(() -> service.rateFor("99999", "GENERAL"))
                .isInstanceOf(OmsException.class)
                .extracting(e -> ((OmsException) e).getErrorCode()).isEqualTo("PRC-503");
    }

    @Test
    void afterEnoughFailuresTheBreakerOpensAndStopsCallingTheProvider() {
        when(client.rate(any())).thenThrow(new IllegalStateException("provider down"));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.rateFor("99999", "GENERAL")).isInstanceOf(OmsException.class);
        }
        verify(client, times(4)).rate(any());

        assertThatThrownBy(() -> service.rateFor("99999", "GENERAL"))
                .isInstanceOf(OmsException.class)
                .extracting(e -> ((OmsException) e).getErrorCode()).isEqualTo("PRC-503");
        verify(client, times(4)).rate(any());      // still 4: the open breaker answered, the provider was not called
    }
}
