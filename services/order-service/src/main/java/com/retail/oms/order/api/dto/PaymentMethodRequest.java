package com.retail.oms.order.api.dto;

import com.retail.oms.order.domain.PaymentGateway;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** A gateway token only; card numbers never reach the platform. */
public record PaymentMethodRequest(
        @NotNull PaymentGateway gateway,
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{3,100}")
        @Schema(description = "Stripe PaymentMethod id (e.g. pm_card_visa) or an approved PayPal order id",
                example = "pm_card_visa")
        String methodToken) {
}
