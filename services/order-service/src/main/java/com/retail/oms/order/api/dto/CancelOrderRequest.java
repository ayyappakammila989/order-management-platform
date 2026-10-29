package com.retail.oms.order.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelOrderRequest(@NotBlank @Size(max = 200) String reason) {
}
