package com.retail.oms.cart.infrastructure.client;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** pricing-service, found in Eureka by name (set oms.pricing-service.url to bypass). */
@FeignClient(name = "pricing-service", url = "${oms.pricing-service.url:}")
public interface PricingClient {

    @PostMapping("/internal/v1/prices/quote")
    Quote quote(@RequestBody QuoteRequest request);

    record QuoteRequest(String currency, String shipToZip, String couponCode, List<Line> lines) {
        public record Line(String sku, int quantity) {
        }
    }

    record Quote(String currency, List<QuoteLine> lines, BigDecimal subtotal, BigDecimal discountTotal,
                 BigDecimal taxTotal, BigDecimal shippingTotal, BigDecimal grandTotal) {
        public record QuoteLine(String sku, int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal tax) {
        }
    }
}
