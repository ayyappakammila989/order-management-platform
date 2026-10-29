package com.retail.oms.pricing.api;

import com.retail.oms.pricing.api.dto.CouponRequest;
import com.retail.oms.pricing.application.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
@Tag(name = "Coupons")
public class CouponController {

    private final CouponService couponService;

    @PostMapping("/validate")
    @Operation(summary = "Is this coupon usable for a cart of this subtotal, and what would it take off?")
    public CouponService.Result validate(@Valid @RequestBody CouponRequest request) {
        return couponService.validate(request.couponCode(), request.subtotal());
    }
}
