package com.retail.oms.pricing.infrastructure.tax;

import java.math.BigDecimal;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** tax-service (architecture 2: sales tax by US state/ZIP). Today the url points to the WireMock stub. */
@FeignClient(name = "tax-service", url = "${oms.tax-service.url:}")
public interface TaxClient {

    @PostMapping("/internal/v1/tax/calculate")
    TaxRateResponse rate(@RequestBody TaxRateRequest request);

    record TaxRateRequest(String zip, String taxCode) {
    }

    record TaxRateResponse(String zip, String state, BigDecimal rate, String source) {
    }
}
