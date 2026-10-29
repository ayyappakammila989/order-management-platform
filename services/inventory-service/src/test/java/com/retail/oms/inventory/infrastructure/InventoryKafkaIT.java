package com.retail.oms.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.retail.oms.events.Topics;
import com.retail.oms.events.inventory.ReleaseStockCommand;
import com.retail.oms.events.inventory.ReserveStockCommand;
import com.retail.oms.events.inventory.SkuQuantity;
import com.retail.oms.events.inventory.StockLevel;
import com.retail.oms.events.inventory.StockReleasedEvent;
import com.retail.oms.events.inventory.StockReserveFailedEvent;
import com.retail.oms.events.inventory.StockReservedEvent;
import com.retail.oms.events.inventory.StockSnapshotEvent;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import com.retail.oms.inventory.application.ReservationService;
import com.retail.oms.inventory.domain.ReleaseReason;
import com.retail.oms.inventory.domain.ReservationStatus;
import com.retail.oms.inventory.support.AbstractInventoryIT;
import com.retail.oms.inventory.support.Infrastructure;
import com.retail.oms.inventory.support.KafkaTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The saga's inventory participant over real Kafka + Schema Registry + PostgreSQL: commands in, replies out through
 * the outbox, duplicates, order events, snapshots, and the failure paths (poison pill, invalid message, database down).
 */
class InventoryKafkaIT extends AbstractInventoryIT {

    static final String DLQ = "oms.inventory.dlq";
    static KafkaTestClient kafka;

    @Autowired ReservationService reservations;
    @Autowired MeterRegistry meters;

    @BeforeAll
    static void client() {
        kafka = new KafkaTestClient();
    }

    @AfterAll
    static void close() {
        kafka.close();
    }

    static ReserveStockCommand reserveCmd(String orderId, String sku, int quantity) {
        return ReserveStockCommand.newBuilder().setCommandId(UUID.randomUUID().toString()).setOccurredAt(Instant.now())
                .setSagaId("saga-" + orderId).setOrderId(orderId).setShipToZip("10001")
                .setLines(List.of(new SkuQuantity(sku, quantity))).build();
    }

    static ReleaseStockCommand releaseCmd(String orderId) {
        return ReleaseStockCommand.newBuilder().setCommandId(UUID.randomUUID().toString()).setOccurredAt(Instant.now())
                .setSagaId("saga-" + orderId).setOrderId(orderId).setReason("payment failed").build();
    }

    static OrderStatusChangedEvent statusEvent(String orderId, String to) {
        return OrderStatusChangedEvent.newBuilder().setEventId(UUID.randomUUID().toString()).setOccurredAt(Instant.now())
                .setOrderId(orderId).setOrderNumber("OMS-20261029-000001").setCustomerId("cust-1")
                .setFromStatus("PENDING").setToStatus(to).setChangedBy("tester").build();
    }

    static String header(ConsumerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    double count(String name) {
        return meters.counter(name).count();
    }

    @Test
    void reserveCommandIsAnsweredOnceEvenWhenDeliveredTwice() throws Exception {
        String sku = newSku("STORE-NYC", 5, "DC-EAST", 5);
        String order = UUID.randomUUID().toString();
        var cmd = reserveCmd(order, sku, 7);
        try (var replies = kafka.reader(Topics.INVENTORY_RESERVED, Topics.INVENTORY_RESERVE_FAILED)) {
            kafka.send(Topics.INVENTORY_RESERVE_CMD, order, cmd);
            kafka.send(Topics.INVENTORY_RESERVE_CMD, order, cmd);          // the orchestrator's relay sent it again

            var reply = (StockReservedEvent) replies.await(order, 1, Duration.ofSeconds(30)).get(0).value();
            assertThat(reply.getCommandId()).isEqualTo(cmd.getCommandId());
            assertThat(reply.getSagaId()).isEqualTo("saga-" + order);
            assertThat(reply.getLines()).extracting(l -> l.getLocationId() + ":" + l.getQuantity())
                    .containsExactlyInAnyOrder("STORE-NYC:5", "DC-EAST:2");
            assertThat(replies.settle(order, Duration.ofSeconds(3))).hasSize(1);   // one reply, not two
        }
        assertThat(totalReserved(sku)).isEqualTo(7);
        assertThat(reservations.findByOrder(UUID.fromString(order))).hasSize(1);
    }

    @Test
    void notEnoughStockIsAnsweredWithTheShortages() throws Exception {
        String sku = newSku("STORE-NYC", 2);
        String order = UUID.randomUUID().toString();
        try (var replies = kafka.reader(Topics.INVENTORY_RESERVED, Topics.INVENTORY_RESERVE_FAILED)) {
            kafka.send(Topics.INVENTORY_RESERVE_CMD, order, reserveCmd(order, sku, 3));

            var reply = replies.await(order, 1, Duration.ofSeconds(30)).get(0);
            assertThat(reply.topic()).isEqualTo(Topics.INVENTORY_RESERVE_FAILED);
            var failed = (StockReserveFailedEvent) reply.value();
            assertThat(failed.getShortages()).singleElement()
                    .satisfies(s -> assertThat(s.getRequested() + "/" + s.getAvailable()).isEqualTo("3/2"));
        }
        assertThat(totalReserved(sku)).isZero();
    }

    @Test
    void releaseCommandIsAnsweredAlsoWhenThereIsNothingLeftToRelease() throws Exception {
        String sku = newSku("DC-WEST", 10);
        String order = UUID.randomUUID().toString();
        try (var replies = kafka.reader(Topics.INVENTORY_RESERVED, Topics.INVENTORY_RELEASED)) {
            kafka.send(Topics.INVENTORY_RESERVE_CMD, order, reserveCmd(order, sku, 4));
            replies.await(order, 1, Duration.ofSeconds(30));

            var release = releaseCmd(order);
            kafka.send(Topics.INVENTORY_RELEASE_CMD, order, release);
            var released = (StockReleasedEvent) replies.await(order, 2, Duration.ofSeconds(30)).get(1).value();
            assertThat(released.getCommandId()).isEqualTo(release.getCommandId());
            assertThat(released.getReason()).isEqualTo("COMPENSATION");
            assertThat(released.getLines()).hasSize(1);
            assertThat(totalReserved(sku)).isZero();

            kafka.send(Topics.INVENTORY_RELEASE_CMD, order, releaseCmd(order));      // a second compensation attempt
            var again = (StockReleasedEvent) replies.await(order, 3, Duration.ofSeconds(30)).get(2).value();
            assertThat(again.getReason()).isEqualTo("NOTHING_TO_RELEASE");
            assertThat(again.getReservationId()).isNull();
        }
    }

    @Test
    void cancelledOrderReleasesItsStockAndConfirmedOrderKeepsIt() throws Exception {
        String sku = newSku("DC-EAST", 10);
        String cancelled = UUID.randomUUID().toString();
        String confirmed = UUID.randomUUID().toString();
        try (var events = kafka.reader(Topics.INVENTORY_RESERVED, Topics.INVENTORY_RELEASED)) {
            kafka.send(Topics.INVENTORY_RESERVE_CMD, cancelled, reserveCmd(cancelled, sku, 2));
            kafka.send(Topics.INVENTORY_RESERVE_CMD, confirmed, reserveCmd(confirmed, sku, 3));
            events.await(cancelled, 1, Duration.ofSeconds(30));
            events.await(confirmed, 1, Duration.ofSeconds(30));

            kafka.send(Topics.ORDER_CANCELLED, cancelled, statusEvent(cancelled, "CANCELLED"));
            kafka.send(Topics.ORDER_CONFIRMED, confirmed, statusEvent(confirmed, "CONFIRMED"));

            var released = (StockReleasedEvent) events.await(cancelled, 2, Duration.ofSeconds(30)).get(1).value();
            assertThat(released.getReason()).isEqualTo("ORDER_CANCELLED");
            assertThat(released.getCommandId()).isNull();                          // not a reply: nobody asked
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(reservations.findByOrder(UUID.fromString(confirmed)).get(0).getExpiresAt()).isNull());
        assertThat(reservations.findByOrder(UUID.fromString(cancelled)).get(0).getReleaseReason())
                .isEqualTo(ReleaseReason.ORDER_CANCELLED);
        assertThat(totalReserved(sku)).isEqualTo(3);
    }

    @Test
    void stockSnapshotFromKafkaSetsOnHandAndPublishesTheChange() throws Exception {
        String sku = newSku("STORE-NYC", 10);
        try (var changes = kafka.reader(Topics.INVENTORY_CHANGED)) {
            kafka.send(Topics.INVENTORY_SNAPSHOT, "STORE-NYC", StockSnapshotEvent.newBuilder()
                    .setSnapshotId(UUID.randomUUID().toString()).setTakenAt(Instant.now()).setLocationId("STORE-NYC")
                    .setSource("POS").setItems(List.of(new StockLevel(sku, 4))).build());

            var change = changes.await(sku, 1, Duration.ofSeconds(30)).get(0);
            assertThat(change.value().toString()).contains("\"onHand\": 4", "\"cause\": \"SNAPSHOT\"");
        }
        assertThat(onHand(sku, "STORE-NYC")).isEqualTo(4);
    }

    @Test
    void poisonPillAndInvalidMessagesGoToTheDeadLetterTopicWithoutRetries() throws Exception {
        String poisonKey = UUID.randomUUID().toString();
        String invalidOrder = "not-a-uuid-" + UUID.randomUUID();
        try (var dlq = kafka.rawReader(DLQ)) {
            kafka.sendRaw(Topics.INVENTORY_RESERVE_CMD, poisonKey, "{\"orderId\":\"x\"}".getBytes(StandardCharsets.UTF_8));
            kafka.send(Topics.INVENTORY_RESERVE_CMD, invalidOrder, reserveCmd(invalidOrder, "SKU-1", 1));

            var poison = dlq.await(poisonKey, 1, Duration.ofSeconds(30)).get(0);
            assertThat(header(poison, "kafka_dlt-exception-cause-fqcn")).contains("PoisonPillException");
            assertThat(new String((byte[]) poison.value(), StandardCharsets.UTF_8)).isEqualTo("{\"orderId\":\"x\"}");

            var invalid = dlq.await(invalidOrder, 1, Duration.ofSeconds(30)).get(0);
            assertThat(header(invalid, "kafka_dlt-exception-cause-fqcn")).contains("InvalidMessageException");
            assertThat(header(invalid, "kafka_dlt-original-topic")).isEqualTo(Topics.INVENTORY_RESERVE_CMD);
        }
    }

    @Test
    void databaseOutageIsRetriedWithBackoffAndTheCommandIsAppliedOnceWhenItIsBack() throws Exception {
        String sku = newSku("DC-EAST", 10);
        String order = UUID.randomUUID().toString();
        var pg = Infrastructure.POSTGRES;
        try (var replies = kafka.reader(Topics.INVENTORY_RESERVED)) {
            pg.getDockerClient().pauseContainerCmd(pg.getContainerId()).exec();
            try {
                kafka.send(Topics.INVENTORY_RESERVE_CMD, order, reserveCmd(order, sku, 2));
                Thread.sleep(6_000);         // first attempts fail: socketTimeout / connection timeout of 2-3 s each
            } finally {
                pg.getDockerClient().unpauseContainerCmd(pg.getContainerId()).exec();
            }

            var reply = replies.await(order, 1, Duration.ofSeconds(60));
            assertThat(reply).hasSize(1);
            assertThat(replies.settle(order, Duration.ofSeconds(3))).hasSize(1);
        }
        assertThat(totalReserved(sku)).isEqualTo(2);
        assertThat(reservations.findByOrder(UUID.fromString(order))).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.ACTIVE));
    }
}
