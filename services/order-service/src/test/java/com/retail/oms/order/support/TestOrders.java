package com.retail.oms.order.support;

import com.retail.oms.order.domain.Address;
import com.retail.oms.order.domain.AddressType;
import com.retail.oms.order.domain.Channel;
import com.retail.oms.order.domain.FulfillmentType;
import com.retail.oms.order.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Test fixtures. */
public final class TestOrders {

    public static final Instant NOW = Instant.parse("2026-10-29T10:15:30Z");

    public static Order order(String customerId) {
        return order(customerId, UUID.randomUUID().toString());
    }

    /** 2 x 19.99 + 1 x 5.00 = 44.98, discount 2.00, tax 3.40, shipping 4.99 -> grand total 51.37 */
    public static Order order(String customerId, String idempotencyKey) {
        return Order.place(UUID.randomUUID(), "OMS-20261029-" + Math.abs(idempotencyKey.hashCode()), customerId,
                Channel.WEB, "USD",
                List.of(new Order.NewLine("SKU-1", 2, new BigDecimal("19.99"), new BigDecimal("2.00"),
                                new BigDecimal("3.00"), FulfillmentType.SHIP),
                        new Order.NewLine("SKU-2", 1, new BigDecimal("5.00"), BigDecimal.ZERO,
                                new BigDecimal("0.40"), FulfillmentType.SHIP)),
                List.of(address(AddressType.SHIP), address(AddressType.BILL)),
                new BigDecimal("4.99"), idempotencyKey, "cart-service", NOW);
    }

    public static Address address(AddressType type) {
        return new Address(type, "Jane Doe", "1 Main St", "New York", "NY", "10001");
    }

    private TestOrders() {
    }
}
