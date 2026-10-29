package com.retail.oms.pricing.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CouponRequest(@NotBlank @Size(max = 40) String couponCode, @NotNull @DecimalMin("0.00") BigDecimal subtotal) {
}
