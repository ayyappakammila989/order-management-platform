package com.retail.oms.order.application;

import com.retail.oms.order.domain.Address;
import com.retail.oms.order.domain.Channel;
import com.retail.oms.order.domain.Order;
import java.math.BigDecimal;
import java.util.List;

/**
 * What the application layer needs to place an order. {@code expectedGrandTotal} is the total the cart showed
 * the customer; when given, the order is refused if the lines add up to anything else.
 */
public record CreateOrderCommand(String customerId, Channel channel, String currency, List<Order.NewLine> lines,
        List<Address> addresses, BigDecimal shippingTotal, BigDecimal expectedGrandTotal) {
}
