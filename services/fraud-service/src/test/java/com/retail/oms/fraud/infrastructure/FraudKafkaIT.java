package com.retail.oms.fraud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retail.oms.events.Topics;
import com.retail.oms.events.common.PaymentMethod;
import com.retail.oms.events.fraud.FraudCancelCommand;
import com.retail.oms.events.fraud.FraudCheckCancelledEvent;
import com.retail.oms.events.fraud.FraudCheckCommand;
import com.retail.oms.events.fraud.FraudDecisionEvent;
import com.retail.oms.events.inventory.SkuQuantity;
import com.retail.oms.fraud.application.CaseNotOpenException;
import com.retail.oms.fraud.application.FraudService;
import com.retail.oms.fraud.support.Infrastructure;
import com.retail.oms.fraud.support.KafkaTestClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * fraud-service as the saga sees it, with real PostgreSQL, Kafka and Schema Registry: commands in, decisions out
 * through the outbox, review cases decided by a supervisor, compensation, duplicates and poison pills.
 * Each test uses its own customer id so the velocity / card-testing history of one test does not leak into another.
 */
@SpringBootTest
@AutoConfigureObservability
class FraudKafkaIT {

    static final Duration WAIT = Duration.ofSeconds(20);
    static final List<String> DECISIONS = List.of(Topics.FRAUD_APPROVED, Topics.FRAUD_REVIEW, Topics.FRAUD_REJECTED);
    static KafkaTestClient kafka;

    @Autowired FraudService fraud;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        Infrastructure.register(registry);
    }

    @BeforeAll
    static void client() {
        kafka = new KafkaTestClient();
    }

    @AfterAll
    static void close() {
        kafka.close();
    }

    @Test
    void cleanOrderIsApprovedOnce() throws Exception {
        var cmd = command(customer(), "85.38", "10001", "pm_card_visa", 2);
        try (var replies = kafka.reader(DECISIONS.toArray(String[]::new))) {
            for (int i = 0; i < 3; i++) {
                kafka.send(Topics.FRAUD_CHECK_CMD, cmd.getOrderId(), cmd, "corr-" + cmd.getCommandId());   // re-delivered twice
            }
            var got = replies.await(cmd.getOrderId(), 1, WAIT);
            var settled = replies.settle(cmd.getOrderId(), Duration.ofSeconds(3));

            assertThat(settled).as("answered once").hasSize(1);
            var reply = (FraudDecisionEvent) got.get(0).value();
            assertThat(got.get(0).topic()).isEqualTo(Topics.FRAUD_APPROVED);
            assertThat(reply.getCommandId()).isEqualTo(cmd.getCommandId());
            assertThat(reply.getSagaId()).isEqualTo(cmd.getSagaId());
            assertThat(reply.getScore()).isZero();
            assertThat(reply.getDecidedBy()).isEqualTo("RULES");
            assertThat(reply.getCaseId()).isNull();
            assertThat(header(got.get(0), "correlationId")).isEqualTo("corr-" + cmd.getCommandId());
        }
        assertThat(count("fraud_check WHERE order_id = ?::uuid", cmd.getOrderId())).isEqualTo(1);
        assertThat(count("fraud_case WHERE order_id = ?::uuid", cmd.getOrderId())).isZero();
    }

    @Test
    void highValueGoesToReviewAndTheSupervisorsDecisionReachesTheSaga() throws Exception {
        var cmd = command(customer(), "1043.12", "10001", "pm_card_visa", 8);
        try (var replies = kafka.reader(DECISIONS.toArray(String[]::new))) {
            kafka.send(Topics.FRAUD_CHECK_CMD, cmd.getOrderId(), cmd);
            var review = replies.await(cmd.getOrderId(), 1, WAIT).get(0);
            var event = (FraudDecisionEvent) review.value();
            assertThat(review.topic()).isEqualTo(Topics.FRAUD_REVIEW);
            assertThat(event.getReasons()).containsExactly("HIGH_ORDER_VALUE");
            assertThat(event.getCaseId()).isNotNull();
            assertThat(jdbc.queryForObject("SELECT status FROM fraud_case WHERE case_id = ?::uuid", String.class,
                    event.getCaseId())).isEqualTo("OPEN");

            fraud.decide(UUID.fromString(event.getCaseId()), true, "supervisor1", "Customer confirmed by phone", "corr-review");

            var all = replies.await(cmd.getOrderId(), 2, WAIT);
            var approved = all.get(1);
            var finalEvent = (FraudDecisionEvent) approved.value();
            assertThat(approved.topic()).isEqualTo(Topics.FRAUD_APPROVED);
            assertThat(finalEvent.getCommandId()).as("same command: the waiting saga matches it").isEqualTo(cmd.getCommandId());
            assertThat(finalEvent.getDecidedBy()).isEqualTo("supervisor1");
            assertThat(finalEvent.getCaseId()).isEqualTo(event.getCaseId());
        }
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT c.status, c.decided_by, k.decision, k.final_decision FROM fraud_case c
                JOIN fraud_check k ON k.check_id = c.check_id WHERE c.order_id = ?::uuid""", UUID.fromString(cmd.getOrderId()));
        assertThat(row).containsEntry("status", "APPROVED").containsEntry("decided_by", "supervisor1")
                .containsEntry("decision", "REVIEW").containsEntry("final_decision", "APPROVED");
        assertThatThrownBy(() -> fraud.decide(UUID.fromString((String) jdbc.queryForObject(
                "SELECT case_id::text FROM fraud_case WHERE order_id = ?::uuid", String.class, UUID.fromString(cmd.getOrderId()))),
                false, "supervisor2", "too late", null))
                .as("decided once").isInstanceOf(CaseNotOpenException.class);
    }

    @Test
    void blockedZipIsRejectedAndRecordedAsACase() throws Exception {
        var cmd = command(customer(), "40.00", "99666", "pm_card_visa", 1);
        try (var replies = kafka.reader(DECISIONS.toArray(String[]::new))) {
            kafka.send(Topics.FRAUD_CHECK_CMD, cmd.getOrderId(), cmd);
            var got = replies.await(cmd.getOrderId(), 1, WAIT).get(0);
            assertThat(got.topic()).isEqualTo(Topics.FRAUD_REJECTED);
            assertThat(((FraudDecisionEvent) got.value()).getReasons()).containsExactly("BLOCKED_ZIP");
            assertThat(((FraudDecisionEvent) got.value()).getScore()).isEqualTo(100);
        }
        assertThat(jdbc.queryForMap("SELECT status, decided_by FROM fraud_case WHERE order_id = ?::uuid",
                UUID.fromString(cmd.getOrderId()))).containsEntry("status", "REJECTED").containsEntry("decided_by", "RULES");
    }

    @Test
    void compensationClosesTheOpenCaseAndIsAlwaysAnswered() throws Exception {
        var cmd = command(customer(), "2500.00", "10001", "pm_card_visa", 1);
        var cancel = cancel(cmd);
        var cancelUnknown = cancel(command(customer(), "1.00", "10001", "pm_x", 1));
        try (var replies = kafka.reader(Topics.FRAUD_REVIEW, Topics.FRAUD_CANCELLED)) {
            kafka.send(Topics.FRAUD_CHECK_CMD, cmd.getOrderId(), cmd);
            replies.await(cmd.getOrderId(), 1, WAIT);
            kafka.send(Topics.FRAUD_CANCEL_CMD, cmd.getOrderId(), cancel);
            kafka.send(Topics.FRAUD_CANCEL_CMD, cmd.getOrderId(), cancel);          // re-delivered
            kafka.send(Topics.FRAUD_CANCEL_CMD, cancelUnknown.getOrderId(), cancelUnknown);

            var got = replies.await(cmd.getOrderId(), 2, WAIT);
            assertThat(replies.settle(cmd.getOrderId(), Duration.ofSeconds(2))).hasSize(2);
            var cancelled = (FraudCheckCancelledEvent) got.get(1).value();
            assertThat(cancelled.getCommandId()).isEqualTo(cancel.getCommandId());
            assertThat(cancelled.getNote()).isEqualTo("CASE_CLOSED");
            var unknown = (FraudCheckCancelledEvent) replies.await(cancelUnknown.getOrderId(), 1, WAIT).get(0).value();
            assertThat(unknown.getNote()).isEqualTo("NOTHING_TO_CANCEL");
        }
        String caseId = jdbc.queryForObject("SELECT case_id::text FROM fraud_case WHERE order_id = ?::uuid", String.class,
                UUID.fromString(cmd.getOrderId()));
        assertThat(jdbc.queryForObject("SELECT status FROM fraud_case WHERE case_id = ?::uuid", String.class, caseId))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT status FROM fraud_check WHERE order_id = ?::uuid", String.class,
                UUID.fromString(cmd.getOrderId()))).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> fraud.decide(UUID.fromString(caseId), true, "supervisor1", "ok", null))
                .isInstanceOf(CaseNotOpenException.class).hasMessageContaining("order was cancelled");
    }

    @Test
    void cardTestingIsSeenAcrossOrdersInTheDatabase() throws Exception {
        String customer = customer();
        try (var replies = kafka.reader(DECISIONS.toArray(String[]::new))) {
            List<String> topics = new java.util.ArrayList<>();
            for (String token : List.of("pm_1", "pm_2", "pm_3", "pm_4")) {
                var cmd = command(customer, "20.00", "10001", token, 1);
                kafka.send(Topics.FRAUD_CHECK_CMD, cmd.getOrderId(), cmd);
                topics.add(replies.await(cmd.getOrderId(), 1, WAIT).get(0).topic());
            }
            assertThat(topics).containsExactly(Topics.FRAUD_APPROVED, Topics.FRAUD_APPROVED, Topics.FRAUD_APPROVED,
                    Topics.FRAUD_REVIEW);
        }
    }

    @Test
    void poisonPillGoesToTheDlqWithoutRetries() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var dlq = kafka.rawReader("oms.fraud.dlq")) {
            kafka.sendRaw(Topics.FRAUD_CHECK_CMD, key, "not avro".getBytes(StandardCharsets.UTF_8));
            var got = dlq.await(key, 1, WAIT).get(0);
            assertThat((byte[]) got.value()).isEqualTo("not avro".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String customer() {
        return "it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static FraudCheckCommand command(String customer, String total, String zip, String token, int quantity) {
        String commandId = UUID.randomUUID().toString();
        return FraudCheckCommand.newBuilder().setCommandId(commandId).setOccurredAt(Instant.now())
                .setSagaId(UUID.randomUUID().toString()).setOrderId(UUID.randomUUID().toString()).setOrderNumber("OMS-IT")
                .setCustomerId(customer).setChannel("WEB").setCurrency("USD").setGrandTotal(new BigDecimal(total))
                .setShipToZip(zip).setPaymentMethod(new PaymentMethod("STRIPE", token))
                .setLines(List.of(new SkuQuantity("SKU-4", quantity))).build();
    }

    private static FraudCancelCommand cancel(FraudCheckCommand check) {
        return FraudCancelCommand.newBuilder().setCommandId(UUID.randomUUID().toString()).setOccurredAt(Instant.now())
                .setSagaId(check.getSagaId()).setOrderId(check.getOrderId()).setReason("AUTHORIZE_PAYMENT failed: declined")
                .build();
    }

    private int count(String fromWhere, String orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class, UUID.fromString(orderId));
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
