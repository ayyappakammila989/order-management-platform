package com.retail.oms.pricing.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record QuoteRequest(
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like USD") String currency,
        @NotBlank @Pattern(regexp = "\\d{5}(-\\d{4})?", message = "must be a US ZIP like 10001 or 10001-1234")
        String shipToZip,
        @Size(max = 40) String couponCode,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Line> lines) {

    public record Line(@NotBlank @Size(max = 40) String sku, @Min(1) @Max(999) int quantity) {
    }
}
