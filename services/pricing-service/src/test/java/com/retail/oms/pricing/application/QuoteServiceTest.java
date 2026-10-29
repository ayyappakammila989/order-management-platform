package com.retail.oms.pricing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.retail.oms.common.web.OmsException;
import com.retail.oms.pricing.config.PricingProperties;
import com.retail.oms.pricing.domain.PriceInfo;
import com.retail.oms.pricing.domain.PromotionKind;
import com.retail.oms.pricing.domain.PromotionRule;
import com.retail.oms.pricing.domain.PromotionScope;
import com.retail.oms.pricing.domain.Quote;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    @Mock PriceCatalog prices;
    @Mock PromotionCatalog promotions;
    @Mock TaxRateService taxRates;
    QuoteService service;

    @BeforeEach
    void setUp() {
        PricingProperties props = new PricingProperties(new BigDecimal("4.99"), new BigDecimal("75.00"),
                Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofHours(24), null);
        service = new QuoteService(prices, promotions, taxRates, props);
    }

    @Test
    void quotesWithTheRateOfTheFiveDigitZipAndAsksForEachTaxCodeOnce() {
        when(prices.priceOf("SKU-1")).thenReturn(new PriceInfo("SKU-1", "USD", new BigDecimal("10.00"), "GENERAL"));
        when(prices.priceOf("SKU-2")).thenReturn(new PriceInfo("SKU-2", "USD", new BigDecimal("20.00"), "GENERAL"));
        when(promotions.automaticSkuPromotions()).thenReturn(List.of());
        when(taxRates.rateFor("10001", "GENERAL")).thenReturn(new BigDecimal("0.10"));

        Quote quote = service.quote("USD", "10001-1234", null,
                List.of(new QuoteService.Line("SKU-1", 1), new QuoteService.Line("SKU-2", 1)));

        assertThat(quote.taxTotal()).isEqualByComparingTo("3.00");
        verify(taxRates, times(1)).rateFor("10001", "GENERAL");
    }

    @Test
    void unknownSkuIsReportedAndNoTaxIsRequested() {
        when(prices.priceOf("NOPE")).thenThrow(new OmsException("PRC-404",
                org.springframework.http.HttpStatus.NOT_FOUND, "Unknown SKU: NOPE"));

        assertThatThrownBy(() -> service.quote("USD", "10001", null, List.of(new QuoteService.Line("NOPE", 1))))
                .isInstanceOf(OmsException.class).hasMessage("Unknown SKU: NOPE");
        verify(taxRates, never()).rateFor(anyString(), anyString());
    }

    @Test
    void currencyMismatchIsRefused() {
        when(prices.priceOf("SKU-1")).thenReturn(new PriceInfo("SKU-1", "USD", new BigDecimal("10.00"), "GENERAL"));

        assertThatThrownBy(() -> service.quote("EUR", "10001", null, List.of(new QuoteService.Line("SKU-1", 1))))
                .isInstanceOf(OmsException.class).hasMessageContaining("priced in USD");
    }

    @Test
    void unknownOrExpiredCouponIsRefused() {
        when(prices.priceOf("SKU-1")).thenReturn(new PriceInfo("SKU-1", "USD", new BigDecimal("10.00"), "GENERAL"));
        when(taxRates.rateFor("10001", "GENERAL")).thenReturn(BigDecimal.ZERO);
        when(promotions.coupon("OLD20")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.quote("USD", "10001", "OLD20", List.of(new QuoteService.Line("SKU-1", 1))))
                .isInstanceOf(OmsException.class).hasMessageContaining("OLD20")
                .extracting(e -> ((OmsException) e).getErrorCode()).isEqualTo("PRC-422");
    }

    @Test
    void validCouponIsApplied() {
        when(prices.priceOf("SKU-4")).thenReturn(new PriceInfo("SKU-4", "USD", new BigDecimal("100.00"), "GENERAL"));
        when(taxRates.rateFor("10001", "GENERAL")).thenReturn(BigDecimal.ZERO);
        when(promotions.automaticSkuPromotions()).thenReturn(List.of());
        when(promotions.coupon("SPRING10")).thenReturn(Optional.of(new PromotionRule(3, "Spring", "SPRING10",
                PromotionScope.ORDER, null, PromotionKind.PERCENT, new BigDecimal("10"), new BigDecimal("50"))));

        Quote quote = service.quote("USD", "10001", "SPRING10", List.of(new QuoteService.Line("SKU-4", 1)));

        assertThat(quote.discountTotal()).isEqualByComparingTo("10.00");
        assertThat(quote.grandTotal()).isEqualByComparingTo("90.00");     // 100 - 10, free shipping from 75
    }
}
