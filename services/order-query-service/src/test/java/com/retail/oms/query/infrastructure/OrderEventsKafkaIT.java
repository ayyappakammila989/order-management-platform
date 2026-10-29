package com.retail.oms.query.infrastructure;

import static com.retail.oms.query.support.Events.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static com.retail.oms.query.support.KafkaClients.awaitDeadLetter;
import static com.retail.oms.query.support.KafkaClients.dlqHas;
import static com.retail.oms.query.support.KafkaClients.header;
import static org.awaitility.Awaitility.await;

import com.retail.oms.events.Topics;
import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import com.retail.oms.kafka.AvroEventDecoder;
import com.retail.oms.query.application.OrderDocument;
import com.retail.oms.query.application.OrderIndex;
import com.retail.oms.query.support.Events;
import com.retail.oms.query.support.AbstractQueryIT;
import com.retail.oms.query.support.Infrastructure;
import com.retail.oms.query.support.KafkaClients;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The whole consumer path with real Kafka, Schema Registry, Elasticsearch and Redis (architecture 12.2, "Kafka
 * consumers"): consume and index, a duplicate event is ignored, and failing messages end up in the dead-letter topic.
 * Failures are real: a poison pill is real garbage bytes, and "Elasticsearch down" is the container being paused.
 */
class OrderEventsKafkaIT extends AbstractQueryIT {

    @Autowired OrderIndex index;
    @Autowired MeterRegistry meters;
    @Autowired StringRedisTemplate redis;
    @Autowired AvroEventDecoder decoder;

    @BeforeAll
    static void topics() throws Exception {
        KafkaClients.init();
    }

    void send(String topic, String orderId, Object event) throws Exception {
        KafkaClients.sendAvro(topic, orderId, event);
    }

    double count(String name) {
        return meters.counter(name).count();
    }

    @Test
    void createdAndCancelledEventsFromKafkaEndUpInTheIndex() throws Exception {
        String id = UUID.randomUUID().toString();
        send(Topics.ORDER_CREATED, id, Events.created(id, "cust-1", T0));
        send(Topics.ORDER_CANCELLED, id, Events.status(UUID.randomUUID().toString(), id, "cust-1", "PENDING", "CANCELLED", T0.plusSeconds(30)));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            OrderDocument doc = index.find(id).orElseThrow();
            assertThat(doc.status()).isEqualTo("CANCELLED");
            assertThat(doc.lines()).hasSize(1);
            assertThat(doc.statusHistory()).hasSize(2);
        });
    }

    @Test
    void duplicateEventIsIgnoredAndCountedAsADuplicate() throws Exception {
        String id = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        var created = Events.created(id, "cust-1", T0);
        var cancelled = Events.status(eventId, id, "cust-1", "PENDING", "CANCELLED", T0.plusSeconds(30));
        send(Topics.ORDER_CREATED, id, created);
        double applied = count("oms.query.events.applied");
        double duplicates = count("oms.query.events.duplicate");

        send(Topics.ORDER_CANCELLED, id, cancelled);
        await().atMost(Duration.ofSeconds(30)).until(() -> redis.hasKey("evt:order-query:" + eventId));
        send(Topics.ORDER_CANCELLED, id, cancelled);        // Kafka delivers it a second time
        send(Topics.ORDER_CANCELLED, id, cancelled);        // and a third time

        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(count("oms.query.events.duplicate") - duplicates).isEqualTo(2));
        assertThat(count("oms.query.events.applied") - applied).isLessThanOrEqualTo(2);   // created + the first cancel only
        OrderDocument doc = index.find(id).orElseThrow();
        assertThat(doc.statusHistory()).hasSize(2);
        assertThat(doc.status()).isEqualTo("CANCELLED");
        assertThat(redis.getExpire("evt:order-query:" + eventId)).isBetween(604_000L, 604_800L);   // 7 days
    }

    @Test
    void ifRedisForgotTheEventTheIndexStillAbsorbsTheRedelivery() throws Exception {
        String id = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        var cancelled = Events.status(eventId, id, "cust-1", "PENDING", "CANCELLED", T0.plusSeconds(30));
        send(Topics.ORDER_CANCELLED, id, cancelled);
        await().atMost(Duration.ofSeconds(30)).until(() -> redis.hasKey("evt:order-query:" + eventId));
        double duplicates = count("oms.query.events.duplicate");

        redis.delete("evt:order-query:" + eventId);         // key expired or Redis was wiped
        send(Topics.ORDER_CANCELLED, id, cancelled);

        await().atMost(Duration.ofSeconds(30)).until(() -> redis.hasKey("evt:order-query:" + eventId));
        assertThat(count("oms.query.events.duplicate")).isEqualTo(duplicates);      // it was processed again...
        assertThat(index.find(id).orElseThrow().statusHistory()).hasSize(1);        // ...with no visible effect
    }

    @Test
    void poisonPillGoesStraightToTheDeadLetterTopicWithoutBlockingTheNextEvent() throws Exception {
        String poisonKey = UUID.randomUUID().toString();
        double deliveries = count("oms.query.events.deliveries");
        double registryErrors = count("oms.query.events.registry_errors");
        KafkaClients.sendRaw(Topics.ORDER_CREATED, poisonKey, "this is not avro".getBytes(StandardCharsets.UTF_8));

        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(poisonKey);

        assertThat(new String(dead.value(), StandardCharsets.UTF_8)).isEqualTo("this is not avro");   // forwarded untouched
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(Topics.ORDER_CREATED);
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("PoisonPillException");
        assertThat(count("oms.query.events.deliveries") - deliveries).isEqualTo(1);   // one attempt, no retries
        assertThat(count("oms.query.events.registry_errors")).isEqualTo(registryErrors);   // not mistaken for an outage

        String id = UUID.randomUUID().toString();
        send(Topics.ORDER_CREATED, id, Events.created(id, "cust-1", T0));            // the partition is not stuck
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(index.find(id)).isPresent());
    }

    @Test
    void eventThatIsWrongInItselfIsNotRetried() throws Exception {
        String id = UUID.randomUUID().toString();
        double deliveries = count("oms.query.events.deliveries");
        send(Topics.ORDER_CANCELLED, id, Events.status(UUID.randomUUID().toString(), id, "cust-1", "PENDING", "not a status!", T0));

        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(id);

        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("InvalidEventException");   // the root cause
        assertThat(decoder.decode(Topics.ORDER_CANCELLED, dead.headers(), dead.value()))   // the original Avro bytes
                .isInstanceOf(OrderStatusChangedEvent.class);
        assertThat(count("oms.query.events.deliveries") - deliveries).isEqualTo(1);      // one attempt, no retries
        assertThat(index.find(id)).isEmpty();
    }

    @Test
    void elasticsearchOutageIsRetriedWithBackoffAndThenTheEventIsDeadLettered() throws Exception {
        String id = UUID.randomUUID().toString();
        double deliveries = count("oms.query.events.deliveries");
        double dead = count("oms.query.events.dead_lettered");
        var es = Infrastructure.ELASTICSEARCH;
        es.getDockerClient().pauseContainerCmd(es.getContainerId()).exec();
        long start = System.nanoTime();
        try {
            send(Topics.ORDER_CREATED, id, Events.created(id, "cust-1", T0));
            ConsumerRecord<String, byte[]> record = awaitDeadLetter(id);
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            // 1 first attempt + 3 retries; waits 0.3 s + 0.6 s + 1.2 s = 2.1 s plus 4 x 1 s socket timeout
            assertThat(count("oms.query.events.deliveries") - deliveries).isEqualTo(4);
            assertThat(took).isGreaterThan(Duration.ofMillis(2100));
            assertThat(count("oms.query.events.dead_lettered") - dead).isEqualTo(1);
            assertThat(header(record, "kafka_dlt-original-topic")).isEqualTo(Topics.ORDER_CREATED);
            assertThat(header(record, "kafka_dlt-original-offset")).isNotNull();
            var original = (OrderCreatedEvent) decoder.decode(Topics.ORDER_CREATED, record.headers(), record.value());
            assertThat(original.getOrderId()).isEqualTo(id);     // the DLQ holds the original Avro bytes: replayable
        } finally {
            es.getDockerClient().unpauseContainerCmd(es.getContainerId()).exec();
        }

        String next = UUID.randomUUID().toString();       // after the outage the consumer simply carries on
        send(Topics.ORDER_CREATED, next, Events.created(next, "cust-1", T0));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(index.find(next)).isPresent());
        // Not asserted: whether the order is in the index. A request that timed out on our side may still have been
        // applied by Elasticsearch once it woke up. That is exactly why the writes are idempotent: replaying the
        // dead-lettered event from the DLQ is safe either way.
    }

    @Test
    void shortElasticsearchOutageIsSurvivedByTheRetriesWithNothingDeadLettered() throws Exception {
        String id = UUID.randomUUID().toString();
        double deliveries = count("oms.query.events.deliveries");
        var es = Infrastructure.ELASTICSEARCH;
        es.getDockerClient().pauseContainerCmd(es.getContainerId()).exec();
        try {
            send(Topics.ORDER_CREATED, id, Events.created(id, "cust-1", T0));
            Thread.sleep(1500);                             // the first attempt has failed by now
        } finally {
            es.getDockerClient().unpauseContainerCmd(es.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(index.find(id)).isPresent());
        assertThat(count("oms.query.events.deliveries") - deliveries).isBetween(2.0, 4.0);   // failed at least once, then worked
        assertThat(dlqHas(id)).isFalse();
    }
}
