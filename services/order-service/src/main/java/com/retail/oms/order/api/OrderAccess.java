package com.retail.oms.order.api;

import com.retail.oms.order.infrastructure.persistence.OrderRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Used in @PreAuthorize: {@code @orderAccess.isOwner(#orderId, authentication)} (architecture 7.4).
 * A customer asking for someone else's order (or one that does not exist) gets 403, so order ids cannot be probed.
 */
@Component("orderAccess")
@RequiredArgsConstructor
public class OrderAccess {

    private final OrderRepository orders;

    public boolean isOwner(UUID orderId, Authentication authentication) {
        return orders.findCustomerIdById(orderId).map(authentication.getName()::equals).orElse(false);
    }
}
