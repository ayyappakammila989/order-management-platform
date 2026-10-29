package com.retail.oms.order.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.retail.oms.common.web.CorrelationId;
import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import com.retail.oms.order.domain.Order;
import com.retail.oms.order.domain.OrderStatus;
import com.retail.oms.order.mapper.OrderEventMapper;
import com.retail.oms.order.support.TestOrders;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

/** Outbox row contents, with the real Avro serializer against an in-memory Schema Registry. */
@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    private static final String REGISTRY = "mock://outbox-writer-test";

    @Mock OutboxEventRepository repository;

    OutboxWriter writer;
    KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer();

    @BeforeEach
    void setUp() {
        writer = new OutboxWriter(repository, new OrderEventMapper(), new AvroEventSerializer(REGISTRY),
                Clock.fixed(TestOrders.NOW, ZoneOffset.UTC));
        deserializer.configure(Map.of("schema.registry.url", REGISTRY, "specific.avro.reader", true), false);
        MDC.put(CorrelationId.MDC_KEY, "corr-123");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void orderCreatedRowCarriesAvroPayloadWithSameEventId() {
        Order order = TestOrders.withLineIds(TestOrders.order("cust-1"));

        writer.orderCreated(order);

        OutboxEvent row = savedRow();
        assertThat(row.getAggregateType()).isEqualTo("order");
        assertThat(row.getAggregateId()).isEqualTo(order.getOrderId().toString());
        assertThat(row.getEventType()).isEqualTo("order.created");
        assertThat(row.getCorrelationId()).isEqualTo("corr-123");
        assertThat(row.getCreatedAt()).isEqualTo(TestOrders.NOW);

        OrderCreatedEvent event = (OrderCreatedEvent) deserializer.deserialize("oms.order.created", row.getPayload());
        assertThat(event.getEventId()).isEqualTo(row.getId().toString());
        assertThat(event.getOrderNumber()).isEqualTo(order.getOrderNumber());
        assertThat(event.getGrandTotal()).isEqualByComparingTo("51.37");
        assertThat(event.getLines()).hasSize(2);
    }

    @Test
    void statusChangeIsRoutedByNewStatus() {
        Order order = TestOrders.order("cust-1");
        Order.StatusChange change = order.changeStatus(OrderStatus.CANCELLED, "customer request", "cust-1",
                TestOrders.NOW).orElseThrow();

        writer.statusChanged(order, change);

        OutboxEvent row = savedRow();
        assertThat(row.getEventType()).isEqualTo("order.cancelled");
        OrderStatusChangedEvent event = (OrderStatusChangedEvent) deserializer.deserialize("oms.order.cancelled",
                row.getPayload());
        assertThat(event.getFromStatus()).isEqualTo("PENDING");
        assertThat(event.getToStatus()).isEqualTo("CANCELLED");
        assertThat(event.getReason()).isEqualTo("customer request");
    }

    private OutboxEvent savedRow() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
