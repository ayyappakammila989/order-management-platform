package com.retail.oms.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.order.domain.Order;
import com.retail.oms.order.support.TestOrders;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderEventMapperTest {

    OrderEventMapper mapper = new OrderEventMapper();

    @Test
    void createdEventCarriesOrderTotalsLinesAndOnlyTheZip() {
        Order order = TestOrders.withLineIds(TestOrders.order("cust-1"));
        UUID eventId = UUID.randomUUID();

        OrderCreatedEvent event = mapper.toCreatedEvent(eventId, order);

        assertThat(event.getEventId()).isEqualTo(eventId.toString());
        assertThat(event.getOrderId()).isEqualTo(order.getOrderId().toString());
        assertThat(event.getCustomerId()).isEqualTo("cust-1");
        assertThat(event.getChannel()).isEqualTo("WEB");
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getSubtotal()).isEqualTo(new BigDecimal("44.98"));
        assertThat(event.getGrandTotal()).isEqualTo(new BigDecimal("51.37"));
        assertThat(event.getOccurredAt()).isEqualTo(TestOrders.NOW);
        assertThat(event.getShipToZip()).isEqualTo("10001");
        assertThat(event.getLines()).extracting(l -> l.getLineId() + ":" + l.getSku() + " x" + l.getQuantity() + " @" + l.getUnitPrice())
                .containsExactly("1:SKU-1 x2 @19.99", "2:SKU-2 x1 @5.00");
        assertThat(event.toString()).doesNotContain("Jane Doe", "Main St");
    }
}
