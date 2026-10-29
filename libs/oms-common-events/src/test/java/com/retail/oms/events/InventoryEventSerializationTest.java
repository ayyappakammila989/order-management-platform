package com.retail.oms.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.retail.oms.events.inventory.LocationQuantity;
import com.retail.oms.events.inventory.ReserveStockCommand;
import com.retail.oms.events.inventory.SkuQuantity;
import com.retail.oms.events.inventory.StockLevel;
import com.retail.oms.events.inventory.StockReleasedEvent;
import com.retail.oms.events.inventory.StockReserveFailedEvent;
import com.retail.oms.events.inventory.StockShortage;
import com.retail.oms.events.inventory.StockSnapshotEvent;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Saga contracts between order-orchestrator and inventory-service survive the Confluent round trip. */
class InventoryEventSerializationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private KafkaAvroSerializer serializer;
    private KafkaAvroDeserializer deserializer;

    @BeforeEach
    void setUp() {
        Map<String, Object> config = Map.of("schema.registry.url", "mock://inventory-events-test", "specific.avro.reader", true);
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
    void reserveCommandWithAndWithoutZip() {
        var withZip = ReserveStockCommand.newBuilder().setCommandId("c-1").setOccurredAt(NOW).setSagaId("s-1")
                .setOrderId("o-1").setShipToZip("10001")
                .setLines(List.of(new SkuQuantity("SKU-1", 2), new SkuQuantity("SKU-2", 1))).build();
        var withoutZip = ReserveStockCommand.newBuilder(withZip).setShipToZip(null).build();

        assertThat(roundTrip(Topics.INVENTORY_RESERVE_CMD, withZip)).isEqualTo(withZip);
        assertThat(roundTrip(Topics.INVENTORY_RESERVE_CMD, withoutZip)).isEqualTo(withoutZip);
    }

    @Test
    void failedReservationCarriesTheShortages() {
        var failed = StockReserveFailedEvent.newBuilder().setEventId("e-1").setOccurredAt(NOW).setCommandId("c-1")
                .setSagaId("s-1").setOrderId("o-1").setReason("Not enough stock")
                .setShortages(List.of(new StockShortage("SKU-5", 3, 2))).build();

        var back = (StockReserveFailedEvent) roundTrip(Topics.INVENTORY_RESERVE_FAILED, failed);
        assertThat(back.getShortages().get(0).getAvailable()).isEqualTo(2);
    }

    @Test
    void releasedEventWithoutCommandForAnOrderCancellation() {
        var released = StockReleasedEvent.newBuilder().setEventId("e-2").setOccurredAt(NOW).setOrderId("o-1")
                .setReservationId("r-1").setReason("ORDER_CANCELLED")
                .setLines(List.of(new LocationQuantity("SKU-1", "DC-EAST", 2))).build();

        var back = (StockReleasedEvent) roundTrip(Topics.INVENTORY_RELEASED, released);
        assertThat(back.getCommandId()).isNull();
        assertThat(back).isEqualTo(released);
    }

    @Test
    void snapshotRoundTrip() {
        var snapshot = StockSnapshotEvent.newBuilder().setSnapshotId("snap-1").setTakenAt(NOW).setLocationId("STORE-NYC")
                .setSource("POS").setItems(List.of(new StockLevel("SKU-1", 7), new StockLevel("SKU-2", 0))).build();

        assertThat(roundTrip(Topics.INVENTORY_SNAPSHOT, snapshot)).isEqualTo(snapshot);
    }
}
