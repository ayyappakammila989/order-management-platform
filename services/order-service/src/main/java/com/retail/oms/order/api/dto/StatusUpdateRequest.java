package com.retail.oms.order.api.dto;

import com.retail.oms.order.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of PATCH /internal/v1/orders/{orderId}/status, sent by the saga orchestrator. */
public record StatusUpdateRequest(@NotNull OrderStatus status, @Size(max = 200) String reason) {
}
