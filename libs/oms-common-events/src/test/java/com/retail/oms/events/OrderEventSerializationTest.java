package com.retail.oms.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderLine;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.AvroTypeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Round trip through the Confluent serializers with an in-memory ("mock://") Schema Registry. */
class OrderEventSerializationTest {

    private KafkaAvroSerializer serializer;
    private KafkaAvroDeserializer deserializer;

    @BeforeEach
    void setUp() {
        Map<String, Object> config = Map.of(
                "schema.registry.url", "mock://events-test",
                "specific.avro.reader", true);
        serializer = new KafkaAvroSerializer();
        serializer.configure(config, false);
        deserializer = new KafkaAvroDeserializer();
        deserializer.configure(config, false);
    }

    @AfterEach
    void tearDown() {
        serializer.close();
        deserializer.close();
    }

    @Test
    void orderCreatedRoundTripKeepsMoneyExact() {
        OrderCreatedEvent event = orderCreated(new BigDecimal("19.99"));

        byte[] bytes = serializer.serialize(Topics.ORDER_CREATED, event);
        Object back = deserializer.deserialize(Topics.ORDER_CREATED, bytes);

        assertThat(bytes[0]).as("Confluent magic byte").isZero();
        assertThat(back).isInstanceOf(OrderCreatedEvent.class).isEqualTo(event);
        OrderCreatedEvent created = (OrderCreatedEvent) back;
        assertThat(created.getGrandTotal()).isEqualByComparingTo("29.98");
        assertThat(created.getLines().get(0).getUnitPrice()).isEqualTo(new BigDecimal("19.99"));
    }

    @Test
    void moneyWithMoreThanTwoDecimalsIsRejectedNotRounded() {
        OrderCreatedEvent event = orderCreated(new BigDecimal("19.999"));

        assertThatThrownBy(() -> serializer.serialize(Topics.ORDER_CREATED, event))
                .hasRootCauseInstanceOf(AvroTypeException.class);
    }

    @Test
    void statusEventRoundTripWithOptionalReason() {
        OrderStatusChangedEvent event = OrderStatusChangedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .setOrderId(UUID.randomUUID().toString())
                .setOrderNumber("OMS-1")
                .setCustomerId("c-1")
                .setFromStatus("PENDING")
                .setToStatus("CANCELLED")
                .setChangedBy("agent1")
                .build();

        Object back = deserializer.deserialize(Topics.ORDER_CANCELLED,
                serializer.serialize(Topics.ORDER_CANCELLED, event));

        assertThat(back).isEqualTo(event);
        assertThat(((OrderStatusChangedEvent) back).getReason()).isNull();
    }

    @Test
    void statusTopicNamesFollowConvention() {
        assertThat(Topics.orderStatus("CONFIRMED")).isEqualTo(Topics.ORDER_CONFIRMED);
        assertThat(Topics.orderStatus("CANCELLED")).isEqualTo(Topics.ORDER_CANCELLED);
    }

    private static OrderCreatedEvent orderCreated(BigDecimal unitPrice) {
        BigDecimal zero = new BigDecimal("0.00");
        return OrderCreatedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .setOrderId(UUID.randomUUID().toString())
                .setOrderNumber("OMS-20261029-000001")
                .setCustomerId("c-1")
                .setChannel("WEB")
                .setStatus("PENDING")
                .setCurrency("USD")
                .setSubtotal(new BigDecimal("29.98"))
                .setDiscountTotal(zero)
                .setTaxTotal(zero)
                .setShippingTotal(zero)
                .setGrandTotal(new BigDecimal("29.98"))
                .setLines(List.of(OrderLine.newBuilder()
                        .setLineId(1L).setSku("SKU-1").setQuantity(2)
                        .setUnitPrice(unitPrice).setDiscount(zero).setTax(zero)
                        .setFulfillmentType("SHIP").build()))
                .setShipToZip("10001")
                .build();
    }
}
