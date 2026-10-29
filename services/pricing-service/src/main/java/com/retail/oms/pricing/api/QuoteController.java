package com.retail.oms.pricing.api;

import com.retail.oms.pricing.api.dto.QuoteRequest;
import com.retail.oms.pricing.application.QuoteService;
import com.retail.oms.pricing.domain.Quote;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service API (role SERVICE); api-gateway blocks /internal/** from outside. */
@RestController
@RequestMapping("/internal/v1/prices")
@RequiredArgsConstructor
@Tag(name = "Prices (internal)")
public class QuoteController {

    private final QuoteService quoteService;

    @PostMapping("/quote")
    @Operation(summary = "Final prices for a cart: promotions, coupon, tax and shipping. Line discount and tax are line totals.")
    public Quote quote(@Valid @RequestBody QuoteRequest request) {
        return quoteService.quote(request.currency(), request.shipToZip(), request.couponCode(),
                request.lines().stream().map(l -> new QuoteService.Line(l.sku(), l.quantity())).toList());
    }
}
