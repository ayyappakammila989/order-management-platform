package com.retail.oms.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.retail.oms.order.config.ClockConfig;
import com.retail.oms.order.domain.AddressType;
import com.retail.oms.order.domain.Channel;
import com.retail.oms.order.domain.FulfillmentType;
import com.retail.oms.order.domain.Order;
import com.retail.oms.order.domain.OrderStatus;
import com.retail.oms.order.infrastructure.messaging.AvroEventSerializer;
import com.retail.oms.order.infrastructure.messaging.OutboxWriter;
import com.retail.oms.order.mapper.OrderEventMapper;
import com.retail.oms.order.support.PostgresTestConfig;
import com.retail.oms.order.support.TestOrders;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The outbox guarantee itself: the order change and its event row are committed, or rolled back, together. */
@DataJpaTest(properties = "oms.order.schema-registry-url=mock://order-outbox-tx-it")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfig.class, OrderService.class, OutboxWriter.class, OrderEventMapper.class,
        AvroEventSerializer.class, ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderOutboxTransactionIT {

    @Autowired OrderService service;
    @Autowired OutboxWriter outboxWriter;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AvroEventSerializer serializer;

    @Test
    void orderAndCreatedEventAreCommittedTogether() {
        Order order = service.create(command("cust-tx-1"), "tx-key-" + UUID.randomUUID(), "cart-service").order();

        assertThat(count("orders", order.getOrderId())).isEqualTo(1);
        assertThat(jdbc.queryForList("select event_type from outbox_event where aggregate_id = ?", String.class,
                order.getOrderId().toString())).containsExactly("order.created");
    }

    @Test
    void failingEventWriteRollsBackTheOrder() {
        doThrow(new SerializationException("schema registry down")).when(serializer)
                .serialize(eq("oms.order.created"), any());
        String key = "tx-key-" + UUID.randomUUID();

        assertThatThrownBy(() -> service.create(command("cust-tx-2"), key, "cart-service"))
                .isInstanceOf(SerializationException.class);

        assertThat(jdbc.queryForObject("select count(*) from orders where idempotency_key = ?", Integer.class, key))
                .as("no order without its event").isZero();
    }

    @Test
    void statusChangeAndEventAreCommittedTogether() {
        Order order = service.create(command("cust-tx-3"), "tx-key-" + UUID.randomUUID(), "cart-service").order();

        service.cancel(order.getOrderId(), "customer request", "cust-tx-3");

        assertThat(jdbc.queryForObject("select status from orders where order_id = ?", String.class,
                order.getOrderId())).isEqualTo(OrderStatus.CANCELLED.name());
        assertThat(jdbc.queryForList("select event_type from outbox_event where aggregate_id = ? order by created_at, event_type desc",
                String.class, order.getOrderId().toString())).containsExactly("order.created", "order.cancelled");
        assertThat(count("order_status_history", order.getOrderId())).isEqualTo(2);
    }

    @Test
    void outboxWriterRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> outboxWriter.orderCreated(TestOrders.order("cust-tx-4")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Integer count(String table, UUID orderId) {
        return jdbc.queryForObject("select count(*) from " + table + " where order_id = ?", Integer.class, orderId);
    }

    private static CreateOrderCommand command(String customerId) {
        return new CreateOrderCommand(customerId, Channel.MOBILE, "USD",
                List.of(new Order.NewLine("SKU-9", 1, new BigDecimal("10.00"), BigDecimal.ZERO,
                        new BigDecimal("0.80"), FulfillmentType.SHIP)),
                List.of(TestOrders.address(AddressType.SHIP)), new BigDecimal("0.00"), null);
    }
}
