package com.retail.oms.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retail.oms.order.support.TestOrders;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class OrderTest {

    @Test
    void totalsAreCalculatedFromLines() {
        Order order = TestOrders.order("cust-1");

        assertThat(order.getSubtotal()).isEqualTo(new BigDecimal("44.98"));
        assertThat(order.getDiscountTotal()).isEqualTo(new BigDecimal("2.00"));
        assertThat(order.getTaxTotal()).isEqualTo(new BigDecimal("3.40"));
        assertThat(order.getShippingTotal()).isEqualTo(new BigDecimal("4.99"));
        assertThat(order.getGrandTotal()).isEqualTo(new BigDecimal("51.37"));
    }

    @Test
    void amountsWithMoreThanTwoDecimalsAreRefusedNotRounded() {
        List<Order.NewLine> lines = List.of(new Order.NewLine("SKU-1", 1, new BigDecimal("9.999"), BigDecimal.ZERO,
                BigDecimal.ZERO, FulfillmentType.SHIP));

        assertThatThrownBy(() -> Order.place(UUID.randomUUID(), "OMS-1", "c", Channel.WEB, "USD", lines, List.of(),
                BigDecimal.ZERO, "k", "x", TestOrders.NOW))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void orderNeedsAtLeastOneLine() {
        assertThatThrownBy(() -> Order.place(UUID.randomUUID(), "OMS-1", "c", Channel.WEB, "USD", List.of(),
                List.of(), BigDecimal.ZERO, "k", "x", TestOrders.NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "PENDING, CONFIRMED, true", "PENDING, CANCELLED, true", "PENDING, SHIPPED, false",
            "CONFIRMED, SHIPPED, true", "CONFIRMED, CANCELLED, true", "CONFIRMED, PENDING, false",
            "SHIPPED, DELIVERED, true", "SHIPPED, CANCELLED, false",
            "DELIVERED, CANCELLED, false", "CANCELLED, CONFIRMED, false"})
    void lifecycleTransitions(OrderStatus from, OrderStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).isEqualTo(allowed);
    }

    @Test
    void cancellableOnlyBeforeShipment() {
        assertThat(OrderStatus.PENDING.isCancellable()).isTrue();
        assertThat(OrderStatus.CONFIRMED.isCancellable()).isTrue();
        assertThat(OrderStatus.SHIPPED.isCancellable()).isFalse();
        assertThat(OrderStatus.DELIVERED.isCancellable()).isFalse();
    }

    @Test
    void addressToStringHidesPersonalData() {
        assertThat(TestOrders.address(AddressType.SHIP).toString()).doesNotContain("Jane", "Main St")
                .contains("10001");
    }
}
