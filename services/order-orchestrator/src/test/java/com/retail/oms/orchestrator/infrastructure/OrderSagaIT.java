package com.retail.oms.orchestrator.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.retail.oms.orchestrator.support.Infrastructure.HTTP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.retail.oms.events.Topics;
import com.retail.oms.events.inventory.LocationQuantity;
import com.retail.oms.events.inventory.ReleaseStockCommand;
import com.retail.oms.events.inventory.ReserveStockCommand;
import com.retail.oms.events.inventory.StockReleasedEvent;
import com.retail.oms.events.inventory.StockReserveFailedEvent;
import com.retail.oms.events.inventory.StockReservedEvent;
import com.retail.oms.events.inventory.StockShortage;
import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderLine;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import com.retail.oms.orchestrator.application.engine.SagaEngine;
import com.retail.oms.orchestrator.domain.SagaInstance;
import com.retail.oms.orchestrator.domain.SagaStatus;
import com.retail.oms.orchestrator.support.AbstractSagaIT;
import com.retail.oms.orchestrator.support.Infrastructure;
import com.retail.oms.orchestrator.support.KafkaTestClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The order placement saga over real Kafka + Schema Registry + PostgreSQL. The test answers the commands the way
 * inventory-service would; WireMock is order-service. Covers the happy path, duplicates, out of stock, a rejected
 * confirm (compensation), order-service down (retry), a step timeout plus a late reply, the customer cancelling
 * mid-saga, and the database going away.
 */
class OrderSagaIT extends AbstractSagaIT {

    static KafkaTestClient kafka;

    @Autowired SagaEngine engine;

    @BeforeAll
    static void client() {
        kafka = new KafkaTestClient();
    }

    @AfterAll
    static void close() {
        kafka.close();
    }

    @BeforeEach
    void orderServiceSaysOk() {
        HTTP.resetAll();
        Infrastructure.stubToken();
        HTTP.stubFor(patch(urlPathEqualTo("/internal/v1/orders/any")).willReturn(aResponse().withStatus(200)));
    }

    static void orderServiceAnswers(String orderId, int status, String body) {
        HTTP.stubFor(patch(urlPathEqualTo(statusPath(orderId))).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    static String statusPath(String orderId) {
        return "/internal/v1/orders/" + orderId + "/status";
    }

    static List<LoggedRequest> patches(String orderId) {
        return HTTP.findAll(patchRequestedFor(urlPathEqualTo(statusPath(orderId))));
    }

    static OrderCreatedEvent created(String orderId) {
        return OrderCreatedEvent.newBuilder().setEventId(UUID.randomUUID().toString()).setOccurredAt(Instant.now())
                .setOrderId(orderId).setOrderNumber("OMS-20261029-" + Math.abs(orderId.hashCode() % 1_000_000))
                .setCustomerId("cust-1").setChannel("WEB").setStatus("PENDING").setCurrency("USD")
                .setSubtotal(new BigDecimal("39.98")).setDiscountTotal(new BigDecimal("0.00"))
                .setTaxTotal(new BigDecimal("3.55")).setShippingTotal(new BigDecimal("4.99"))
                .setGrandTotal(new BigDecimal("48.52"))
                .setLines(List.of(OrderLine.newBuilder().setLineId(1L).setSku("SKU-1").setQuantity(2)
                        .setUnitPrice(new BigDecimal("19.99")).setDiscount(new BigDecimal("0.00"))
                        .setTax(new BigDecimal("3.55")).setFulfillmentType("SHIP").build()))
                .setShipToZip("10001").build();
    }

    static StockReservedEvent reserved(ReserveStockCommand cmd) {
        return StockReservedEvent.newBuilder().setEventId(UUID.randomUUID().toString()).setOccurredAt(Instant.now())
                .setCommandId(cmd.getCommandId()).setSagaId(cmd.getSagaId()).setOrderId(cmd.getOrderId())
                .setReservationId(UUID.randomUUID().toString()).setExpiresAt(Instant.now().plusSeconds(1800))
                .setLines(List.of(new LocationQuantity("SKU-1", "STORE-NYC", 2))).build();
    }

    static StockReleasedEvent released(ReleaseStockCommand cmd, String reason) {
        return StockReleasedEvent.newBuilder().setEventId(UUID.randomUUID().toString()).setOccurredAt(Instant.now())
                .setCommandId(cmd.getCommandId()).setSagaId(cmd.getSagaId()).setOrderId(cmd.getOrderId())
                .setReason(reason).setLines(List.of()).build();
    }

    SagaInstance awaitSaga(String orderId, SagaStatus status) {
        await().atMost(Duration.ofSeconds(30)).until(() -> engine.findByOrder(UUID.fromString(orderId))
                .map(s -> s.getStatus() == status).orElse(false));
        return engine.findByOrder(UUID.fromString(orderId)).orElseThrow();
    }

    List<String> trail(SagaInstance s) {
        return engine.steps(s.getSagaId()).stream().map(l -> l.getStep() + " " + l.getOutcome()).toList();
    }

    @Test
    void happyPathReservesStockThenConfirmsTheOrderWithAServiceToken() throws Exception {
        String order = UUID.randomUUID().toString();
        orderServiceAnswers(order, 200, "{}");
        try (var commands = kafka.reader(Topics.INVENTORY_RESERVE_CMD)) {
            kafka.send(Topics.ORDER_CREATED, order, created(order));

            var cmd = (ReserveStockCommand) commands.await(order, 1, Duration.ofSeconds(30)).get(0).value();
            assertThat(cmd.getShipToZip()).isEqualTo("10001");
            assertThat(cmd.getLines()).singleElement().satisfies(l -> assertThat(l.getSku() + "x" + l.getQuantity()).isEqualTo("SKU-1x2"));

            kafka.send(Topics.INVENTORY_RESERVED, order, reserved(cmd));
        }

        SagaInstance saga = awaitSaga(order, SagaStatus.COMPLETED);
        HTTP.verify(1, patchRequestedFor(urlPathEqualTo(statusPath(order)))
                .withHeader("Authorization", equalTo("Bearer test-service-token"))
                .withRequestBody(matchingJsonPath("$.status", equalTo("CONFIRMED"))));
        assertThat(saga.getPayload().result("reservationId")).isNotNull();
        assertThat(trail(saga)).containsExactly("SAGA STARTED", "RESERVE_STOCK STARTED", "RESERVE_STOCK SUCCEEDED",
                "CONFIRM_ORDER STARTED", "CONFIRM_ORDER SUCCEEDED", "SAGA COMPLETED");
    }

    @Test
    void duplicateEventsAndRepliesDoNothingTwice() throws Exception {
        String order = UUID.randomUUID().toString();
        orderServiceAnswers(order, 200, "{}");
        var event = created(order);
        try (var commands = kafka.reader(Topics.INVENTORY_RESERVE_CMD)) {
            kafka.send(Topics.ORDER_CREATED, order, event);
            kafka.send(Topics.ORDER_CREATED, order, event);                       // same event again
            kafka.send(Topics.ORDER_CREATED, order, created(order));              // another event, same order
            var cmd = (ReserveStockCommand) commands.await(order, 1, Duration.ofSeconds(30)).get(0).value();
            assertThat(commands.settle(order, Duration.ofSeconds(3))).hasSize(1);

            var reply = reserved(cmd);
            kafka.send(Topics.INVENTORY_RESERVED, order, reply);
            kafka.send(Topics.INVENTORY_RESERVED, order, reply);
        }
        awaitSaga(order, SagaStatus.COMPLETED);
        Thread.sleep(1500);
        assertThat(patches(order)).hasSize(1);
    }

    @Test
    void outOfStockCancelsTheOrderWithoutAnythingToRelease() throws Exception {
        String order = UUID.randomUUID().toString();
        orderServiceAnswers(order, 200, "{}");
        try (var commands = kafka.reader(Topics.INVENTORY_RESERVE_CMD, Topics.INVENTORY_RELEASE_CMD)) {
            kafka.send(Topics.ORDER_CREATED, order, created(order));
            var cmd = (ReserveStockCommand) commands.await(order, 1, Duration.ofSeconds(30)).get(0).value();
            kafka.send(Topics.INVENTORY_RESERVE_FAILED, order, StockReserveFailedEvent.newBuilder()
                    .setEventId(UUID.randomUUID().toString()).setOccurredAt(Instant.now()).setCommandId(cmd.getCommandId())
                    .setSagaId(cmd.getSagaId()).setOrderId(order).setReason("Not enough stock: [SKU-1 requested 2, available 1]")
                    .setShortages(List.of(new StockShortage("SKU-1", 2, 1))).build());

            SagaInstance saga = awaitSaga(order, SagaStatus.COMPENSATED);
            assertThat(commands.settle(order, Duration.ofSeconds(2))).hasSize(1);   // no release-cmd
            assertThat(trail(saga)).containsExactly("SAGA STARTED", "RESERVE_STOCK STARTED", "RESERVE_STOCK FAILED",
                    "SAGA ABORTED");
        }
        HTTP.verify(1, patchRequestedFor(urlPathEqualTo(statusPath(order)))
                .withRequestBody(matchingJsonPath("$.status", equalTo("CANCELLED")))
                .withRequestBody(matchingJsonPath("$.reason", com.github.tomakehurst.wiremock.client.WireMock.containing("SKU-1 requested 2"))));
    }

    @Test
    void confirmRefusedByOrderServiceReleasesTheStockAndCancels() throws Exception {
        String order = UUID.randomUUID().toString();
        HTTP.stubFor(patch(urlPathEqualTo(statusPath(order))).withRequestBody(matchingJsonPath("$.status", equalTo("CONFIRMED")))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"errorCode\":\"ORD-409\",\"message\":\"Cannot change status from CANCELLED to CONFIRMED\"}")));
        HTTP.stubFor(patch(urlPathEqualTo(statusPath(order))).withRequestBody(matchingJsonPath("$.status", equalTo("CANCELLED")))
                .willReturn(aResponse().withStatus(200)));
        try (var commands = kafka.reader(Topics.INVENTORY_RESERVE_CMD, Topics.INVENTORY_RELEASE_CMD)) {
            kafka.send(Topics.ORDER_CREATED, order, created(order));
            var reserve = (ReserveStockCommand) commands.await(order, 1, Duration.ofSeconds(30)).get(0).value();
            kafka.send(Topics.INVENTORY_RESERVED, order, reserved(reserve));

            var release = (ReleaseStockCommand) commands.await(order, 2, Duration.ofSeconds(30)).get(1).value();
            assertThat(release.getReason()).contains("ORD-409");
            assertThat(engine.findByOrder(UUID.fromString(order)).orElseThrow().getStatus()).isEqualTo(SagaStatus.COMPENSATING);
            kafka.send(Topics.INVENTORY_RELEASED, order, released(release, "COMPENSATION"));
        }

        SagaInstance saga = awaitSaga(order, SagaStatus.COMPENSATED);
        assertThat(trail(saga)).containsSubsequence("RESERVE_STOCK SUCCEEDED", "CONFIRM_ORDER FAILED",
                "RESERVE_STOCK COMPENSATING", "RESERVE_STOCK COMPENSATED", "SAGA ABORTED");
        assertThat(patches(order)).extracting(r -> r.getBodyAsString().contains("CANCELLED") ? "CANCELLED" : "CONFIRMED")
                .containsExactly("CONFIRMED", "CANCELLED");                          // cancel only after the release
    }

    @Test
    void orderServiceDownIsRetriedWithBackoffAndTheOrderStillGetsConfirmed() throws Exception {
        String order = UUID.randomUUID().toString();
        HTTP.stubFor(patch(urlPathEqualTo(statusPath(order))).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("still down"));
        HTTP.stubFor(patch(urlPathEqualTo(statusPath(order))).inScenario("flaky").whenScenarioStateIs("still down")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("up"));
        HTTP.stubFor(patch(urlPathEqualTo(statusPath(order))).inScenario("flaky").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200)));
        try (var commands = kafka.reader(Topics.INVENTORY_RESERVE_CMD, Topics.INVENTORY_RELEASE_CMD)) {
            kafka.send(Topics.ORDER_CREATED, order, created(order));
            var cmd = (ReserveStockCommand) commands.await(order, 1, Duration.ofSeconds(30)).get(0).value();
            kafka.send(Topics.INVENTORY_RESERVED, order, reserved(cmd));

            SagaInstance saga = awaitSaga(order, SagaStatus.COMPLETED);
            assertThat(patches(order)).hasSize(3);                                   // 503, 503, 200
            assertThat(commands.settle(order, Duration.ofSeconds(2))).hasSize(1);     // nothing compensated
            assertThat(trail(saga)).containsOnlyOnce("CONFIRM_ORDER SUCCEEDED");     // failed attempts rolled back
        }
    }

    @Test
    void noReplyInTimeCompensatesAndALateReservationIsReleasedAgain() throws Exception {
        String order = UUID.randomUUID().toString();
        orderServiceAnswers(order, 200, "{}");
        try (var commands = kafka.reader(Topics.INVENTORY_RESERVE_CMD, Topics.INVENTORY_RELEASE_CMD)) {
            kafka.send(Topics.ORDER_CREATED, order, created(order));
            var reserve = (ReserveStockCommand) commands.await(order, 1, Duration.ofSeconds(30)).get(0).value();
            // inventory-service is silent: after the 10 s step timeout the saga undoes what may have happened
            var release = (ReleaseStockCommand) commands.await(order, 2, Duration.ofSeconds(30)).get(1).value();
            kafka.send(Topics.INVENTORY_RELEASED, order, released(release, "NOTHING_TO_RELEASE"));
            SagaInstance saga = awaitSaga(order, SagaStatus.COMPENSATED);
            HTTP.verify(1, patchRequestedFor(urlPathEqualTo(statusPath(order)))
                    .withRequestBody(matchingJsonPath("$.reason", equalTo("RESERVE_STOCK timed out"))));

            kafka.send(Topics.INVENTORY_RESERVED, order, reserved(reserve));        // inventory wakes up after all
            var again = (ReleaseStockCommand) commands.await(order, 3, Duration.ofSeconds(30)).get(2).value();
            assertThat(again.getCommandId()).isNotEqualTo(release.getCommandId());
            await().atMost(Duration.ofSeconds(10)).until(() -> trail(saga).contains("RESERVE_STOCK LATE_REPLY"));
            assertThat(trail(saga)).containsSubsequence("RESERVE_STOCK TIMED_OUT", "RESERVE_STOCK COMPENSATING",
                    "RESERVE_STOCK COMPENSATED", "SAGA ABORTED", "RESERVE_STOCK LATE_REPLY");
        }
    }

    @Test
    void customerCancellingWhileStockIsBeingReservedStopsTheSagaWithoutCancellingAgain() throws Exception {
        String order = UUID.randomUUID().toString();
        orderServiceAnswers(order, 200, "{}");
        try (var commands = kafka.reader(Topics.INVENTORY_RESERVE_CMD, Topics.INVENTORY_RELEASE_CMD)) {
            kafka.send(Topics.ORDER_CREATED, order, created(order));
            commands.await(order, 1, Duration.ofSeconds(30));
            kafka.send(Topics.ORDER_CANCELLED, order, OrderStatusChangedEvent.newBuilder()
                    .setEventId(UUID.randomUUID().toString()).setOccurredAt(Instant.now()).setOrderId(order)
                    .setOrderNumber("OMS-x").setCustomerId("cust-1").setFromStatus("PENDING").setToStatus("CANCELLED")
                    .setReason("changed my mind").setChangedBy("cust-1").build());

            var release = (ReleaseStockCommand) commands.await(order, 2, Duration.ofSeconds(30)).get(1).value();
            assertThat(release.getReason()).contains("changed my mind");
            kafka.send(Topics.INVENTORY_RELEASED, order, released(release, "COMPENSATION"));
        }
        awaitSaga(order, SagaStatus.COMPENSATED);
        assertThat(patches(order)).isEmpty();                                        // already cancelled: no PATCH
    }

    @Test
    void databaseOutageWhileAReplyArrivesIsRetriedAndTheSagaCompletesOnce() throws Exception {
        String order = UUID.randomUUID().toString();
        orderServiceAnswers(order, 200, "{}");
        var pg = Infrastructure.POSTGRES;
        try (var commands = kafka.reader(Topics.INVENTORY_RESERVE_CMD)) {
            kafka.send(Topics.ORDER_CREATED, order, created(order));
            var cmd = (ReserveStockCommand) commands.await(order, 1, Duration.ofSeconds(30)).get(0).value();
            pg.getDockerClient().pauseContainerCmd(pg.getContainerId()).exec();
            try {
                kafka.send(Topics.INVENTORY_RESERVED, order, reserved(cmd));
                // attempts fail on the 3 s socket timeout and back off. The outage must end before the step timeout:
                // after it, the timeout job may win and compensate instead (also consistent, but another test)
                Thread.sleep(5_000);
            } finally {
                pg.getDockerClient().unpauseContainerCmd(pg.getContainerId()).exec();
            }
        }
        SagaInstance saga = awaitSaga(order, SagaStatus.COMPLETED);
        assertThat(trail(saga)).containsOnlyOnce("CONFIRM_ORDER SUCCEEDED");
        assertThat(patches(order)).isNotEmpty();      // repeating the PATCH would be harmless (same status = no-op)
    }

    @Test
    void poisonPillGoesToTheDeadLetterTopic() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var dlq = kafka.rawReader("oms.order-orchestrator.dlq")) {
            kafka.sendRaw(Topics.INVENTORY_RESERVED, key, "not avro".getBytes(StandardCharsets.UTF_8));
            ConsumerRecord<String, Object> dead = dlq.await(key, 1, Duration.ofSeconds(30)).get(0);
            assertThat(new String((byte[]) dead.value(), StandardCharsets.UTF_8)).isEqualTo("not avro");
        }
    }
}
