package com.retail.oms.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.retail.oms.events.common.PaymentMethod;
import com.retail.oms.events.fraud.FraudCheckCommand;
import com.retail.oms.events.fraud.FraudDecisionEvent;
import com.retail.oms.events.inventory.SkuQuantity;
import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderLine;
import com.retail.oms.events.payment.AuthorizePaymentCommand;
import com.retail.oms.events.payment.GatewayCommand;
import com.retail.oms.events.payment.GatewayResult;
import com.retail.oms.events.payment.PaymentLine;
import com.retail.oms.events.shipment.ShipmentShippedEvent;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Step 3B contracts (fraud, payment, gateway, shipment) survive the Confluent round trip; old events still decode. */
class PaymentFraudEventSerializationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private KafkaAvroSerializer serializer;
    private KafkaAvroDeserializer deserializer;

    @BeforeEach
    void setUp() {
        Map<String, Object> config = Map.of("schema.registry.url", "mock://payment-events-test", "specific.avro.reader", true);
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

    private Object roundTrip(String topic, Object message) {
        return deserializer.deserialize(topic, serializer.serialize(topic, message));
    }

    @Test
    void authorizeCommandKeepsMoneyExact() {
        var cmd = AuthorizePaymentCommand.newBuilder().setCommandId("c-1").setOccurredAt(NOW).setSagaId("s-1")
                .setOrderId("o-1").setOrderNumber("OMS-1").setCustomerId("cust-1").setIdempotencyKey("auth-o-1")
                .setCurrency("USD").setAmount(new BigDecimal("85.38"))
                .setPaymentMethod(new PaymentMethod("STRIPE", "pm_card_visa"))
                .setLines(List.of(new PaymentLine("SKU-1", 2, new BigDecimal("40.10"))))
                .setShippingAmount(new BigDecimal("4.99")).build();

        var back = (AuthorizePaymentCommand) roundTrip(Topics.PAYMENT_AUTHORIZE_CMD, cmd);
        assertThat(back).isEqualTo(cmd);
        assertThat(back.getAmount()).isEqualByComparingTo("85.38");
    }

    @Test
    void gatewayCommandAndResultDefaults() {
        var cmd = GatewayCommand.newBuilder().setRequestId("t-1").setOccurredAt(NOW).setIdempotencyKey("auth-o-1")
                .setOperation("AUTHORIZE").setGateway("PAYPAL").setPaymentId("p-1").setOrderId("o-1")
                .setAmount(new BigDecimal("10.00")).setCurrency("USD").setMethodToken("PAYPAL-ORDER-1").build();
        var result = GatewayResult.newBuilder().setEventId("e-1").setOccurredAt(NOW).setRequestId("t-1")
                .setIdempotencyKey("auth-o-1").setOperation("AUTHORIZE").setGateway("PAYPAL").setOutcome("PENDING_RETRY")
                .build();

        var cmdBack = (GatewayCommand) roundTrip(Topics.PAYMENT_GATEWAY_CMD, cmd);
        var resultBack = (GatewayResult) roundTrip(Topics.PAYMENT_GATEWAY_RESULT, result);
        assertThat(cmdBack.getFinalCapture()).isTrue();
        assertThat(cmdBack.getAttempt()).isEqualTo(1);
        assertThat(resultBack.getReplayed()).isFalse();
        assertThat(resultBack.getHttpStatus()).isNull();
    }

    @Test
    void fraudCommandAndDecision() {
        var cmd = FraudCheckCommand.newBuilder().setCommandId("c-2").setOccurredAt(NOW).setSagaId("s-1").setOrderId("o-1")
                .setOrderNumber("OMS-1").setCustomerId("cust-1").setChannel("WEB").setCurrency("USD")
                .setGrandTotal(new BigDecimal("1200.00")).setLines(List.of(new SkuQuantity("SKU-1", 12))).build();
        var decision = FraudDecisionEvent.newBuilder().setEventId("e-2").setOccurredAt(NOW).setCommandId("c-2")
                .setSagaId("s-1").setOrderId("o-1").setCheckId("k-1").setDecision("REVIEW").setScore(70)
                .setReasons(List.of("HIGH_ORDER_VALUE", "LARGE_QUANTITY")).setCaseId("case-1").setDecidedBy("RULES").build();

        assertThat(roundTrip(Topics.FRAUD_CHECK_CMD, cmd)).isEqualTo(cmd);
        assertThat(roundTrip(Topics.fraudDecision("REVIEW"), decision)).isEqualTo(decision);
        assertThat(Topics.fraudDecision("REVIEW")).isEqualTo(Topics.FRAUD_REVIEW);
    }

    @Test
    void shipmentShipped() {
        var shipped = ShipmentShippedEvent.newBuilder().setEventId("e-3").setOccurredAt(NOW).setShipmentId("sh-1")
                .setOrderId("o-1").setLines(List.of(new SkuQuantity("SKU-1", 1))).build();
        assertThat(roundTrip(Topics.SHIPMENT_SHIPPED, shipped)).isEqualTo(shipped);
    }

    @Test
    void orderCreatedWithPaymentMethodIsBackwardCompatible() {
        Schema current = OrderCreatedEvent.getClassSchema();
        // the step 2 schema = today's without the new optional field
        Schema previous = withoutField(current, "paymentMethod");

        assertThat(SchemaCompatibility.checkReaderWriterCompatibility(current, previous).getType())
                .as("new readers can read old events").isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);

        var event = OrderCreatedEvent.newBuilder().setEventId("e-4").setOccurredAt(NOW).setOrderId("o-1")
                .setOrderNumber("OMS-1").setCustomerId("c").setChannel("WEB").setStatus("PENDING").setCurrency("USD")
                .setSubtotal(BigDecimal.TEN.setScale(2)).setDiscountTotal(BigDecimal.ZERO.setScale(2))
                .setTaxTotal(BigDecimal.ZERO.setScale(2)).setShippingTotal(BigDecimal.ZERO.setScale(2))
                .setGrandTotal(BigDecimal.TEN.setScale(2))
                .setLines(List.of(OrderLine.newBuilder().setLineId(1).setSku("SKU-1").setQuantity(1)
                        .setUnitPrice(BigDecimal.TEN.setScale(2)).setDiscount(BigDecimal.ZERO.setScale(2))
                        .setTax(BigDecimal.ZERO.setScale(2)).setFulfillmentType("SHIP").build()))
                .setPaymentMethod(new PaymentMethod("PAYPAL", "PAYPAL-ORDER-9")).build();
        assertThat(((OrderCreatedEvent) roundTrip(Topics.ORDER_CREATED, event)).getPaymentMethod().getGateway())
                .isEqualTo("PAYPAL");
    }

    private static Schema withoutField(Schema schema, String name) {
        var fields = schema.getFields().stream().filter(f -> !f.name().equals(name))
                .map(f -> new Schema.Field(f, f.schema())).toList();
        return Schema.createRecord(schema.getName(), schema.getDoc(), schema.getNamespace(), false, fields);
    }
}
