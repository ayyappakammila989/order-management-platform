package com.retail.oms.order.api;

import com.retail.oms.common.web.security.OmsJwt;
import com.retail.oms.order.api.dto.CancelOrderRequest;
import com.retail.oms.order.api.dto.OrderResponse;
import com.retail.oms.order.application.OrderService;
import com.retail.oms.order.mapper.OrderApiMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public order API (behind api-gateway and the BFFs). Role rules from architecture 7.4. */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderApiMapper mapper;

    @GetMapping("/{orderId}")
    @Operation(summary = "Order by id: customers see only their own orders, staff and services see all")
    @PreAuthorize("hasAnyRole('SUPPORT_AGENT', 'SUPPORT_SUPERVISOR', 'OPS_ADMIN', 'FINANCE', 'SERVICE')"
            + " or (hasRole('CUSTOMER') and @orderAccess.isOwner(#orderId, authentication))")
    public OrderResponse get(@PathVariable UUID orderId) {
        return mapper.toResponse(orderService.get(orderId));
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel before shipment (ORD-409 afterwards); cancelling twice is a no-op")
    @PreAuthorize("hasAnyRole('SUPPORT_AGENT', 'SUPPORT_SUPERVISOR', 'SERVICE')"
            + " or (hasRole('CUSTOMER') and @orderAccess.isOwner(#orderId, authentication))")
    public OrderResponse cancel(@PathVariable UUID orderId, @Valid @RequestBody CancelOrderRequest request,
            Authentication authentication) {
        return mapper.toResponse(orderService.cancel(orderId, request.reason(), OmsJwt.actor(authentication)));
    }
}
