package com.retail.oms.pricing.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.retail.oms.common.web.OmsCommonWebAutoConfiguration;
import com.retail.oms.common.web.OmsException;
import com.retail.oms.pricing.application.CouponService;
import com.retail.oms.pricing.application.QuoteService;
import com.retail.oms.pricing.config.SecurityConfig;
import com.retail.oms.pricing.domain.Quote;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {QuoteController.class, CouponController.class})
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OmsCommonWebAutoConfiguration.class)
class PricingApiTest {

    static final String BODY = """
            {"currency":"USD","shipToZip":"10001","couponCode":"SPRING10",
             "lines":[{"sku":"SKU-1","quantity":2}]}""";

    @Autowired MockMvc mvc;
    @MockitoBean QuoteService quoteService;
    @MockitoBean CouponService couponService;

    static org.springframework.test.web.servlet.request.RequestPostProcessor service() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    static org.springframework.test.web.servlet.request.RequestPostProcessor customer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Test
    void quoteWithoutTokenIs401() throws Exception {
        mvc.perform(post("/internal/v1/prices/quote").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(quoteService);
    }

    @Test
    void quoteAsCustomerIs403() throws Exception {
        mvc.perform(post("/internal/v1/prices/quote").with(customer()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC-403"));
        verifyNoInteractions(quoteService);
    }

    @Test
    void quoteAsServiceReturnsThePrices() throws Exception {
        Quote.QuoteLine line = new Quote.QuoteLine("SKU-1", 2, new BigDecimal("19.99"), new BigDecimal("0.00"),
                new BigDecimal("3.55"), new BigDecimal("0.08875"));
        when(quoteService.quote(eq("USD"), eq("10001"), eq("SPRING10"), anyList())).thenReturn(new Quote("USD",
                List.of(line), new BigDecimal("39.98"), new BigDecimal("0.00"), new BigDecimal("3.55"),
                new BigDecimal("4.99"), new BigDecimal("48.52"), List.of()));

        mvc.perform(post("/internal/v1/prices/quote").with(service()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grandTotal").value(48.52))
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].tax").value(3.55));
    }

    @Test
    void invalidRequestIs400WithFieldErrors() throws Exception {
        mvc.perform(post("/internal/v1/prices/quote").with(service()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"usd","shipToZip":"1","lines":[{"sku":"SKU-1","quantity":0}]}"""))
                .andExpect(status().isBadRequest());
        verify(quoteService, never()).quote(any(), any(), any(), any());
    }

    @Test
    void businessErrorsUseTheStandardErrorBody() throws Exception {
        when(quoteService.quote(any(), any(), any(), any())).thenThrow(
                new OmsException("PRC-404", HttpStatus.NOT_FOUND, "Unknown SKU: SKU-1"));

        mvc.perform(post("/internal/v1/prices/quote").with(service()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRC-404"))
                .andExpect(jsonPath("$.message").value("Unknown SKU: SKU-1"));
    }

    @Test
    void taxOutageIs503() throws Exception {
        when(quoteService.quote(any(), any(), any(), any())).thenThrow(
                new OmsException("PRC-503", HttpStatus.SERVICE_UNAVAILABLE, "Tax cannot be calculated right now"));

        mvc.perform(post("/internal/v1/prices/quote").with(service()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void couponValidationIsOpenToSignedInUsersOnly() throws Exception {
        String body = "{\"couponCode\":\"SPRING10\",\"subtotal\":60.00}";
        mvc.perform(post("/api/v1/coupons/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        when(couponService.validate("SPRING10", new BigDecimal("60.00")))
                .thenReturn(new CouponService.Result(true, null, "Spring", new BigDecimal("6.00")));
        mvc.perform(post("/api/v1/coupons/validate").with(customer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.discount").value(6.00));
    }
}
