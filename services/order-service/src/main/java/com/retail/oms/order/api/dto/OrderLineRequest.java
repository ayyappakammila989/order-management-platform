package com.retail.oms.order.api.dto;

import com.retail.oms.order.domain.FulfillmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Discount and tax are totals for the whole line (not per unit). */
public record OrderLineRequest(
        @NotBlank @Size(max = 40) String sku,
        @Min(1) @Max(999) int quantity,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) @Schema(defaultValue = "0.00") BigDecimal discount,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) @Schema(defaultValue = "0.00") BigDecimal tax,
        @NotNull FulfillmentType fulfillmentType) {
}
