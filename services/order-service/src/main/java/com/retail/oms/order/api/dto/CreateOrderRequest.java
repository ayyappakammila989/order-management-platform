package com.retail.oms.order.api.dto;

import com.retail.oms.order.domain.Channel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Body of POST /internal/v1/orders, sent by cart-service at checkout with prices already final. */
public record CreateOrderRequest(
        @NotBlank @Size(max = 64) @Schema(description = "Keycloak user id (sub) of the customer")
        String customerId,
        @NotNull Channel channel,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like USD") String currency,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull OrderLineRequest> lines,
        @NotNull @Valid AddressRequest shippingAddress,
        @Valid @Schema(description = "Defaults to no billing address (card billing is checked by the payment gateway)")
        AddressRequest billingAddress,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal shippingTotal,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
        @Schema(description = "Total the customer saw; the order is refused (ORD-422) if the lines add up differently")
        BigDecimal expectedGrandTotal) {
}
