package com.retail.oms.query.infrastructure;

import static com.retail.oms.query.support.Events.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.retail.oms.events.Topics;
import com.retail.oms.query.application.OrderDocument;
import com.retail.oms.query.application.OrderIndex;
import com.retail.oms.query.support.Events;
import com.retail.oms.query.support.AbstractQueryIT;
import com.retail.oms.query.support.Infrastructure;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
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

    static final String DLQ = "oms.order-query.dlq";

    @Autowired OrderIndex index;
    @Autowired MeterRegistry meters;
    @Autowired StringRedisTemplate redis;

    static KafkaProducer<String, Object> avro;
    static KafkaProducer<String, byte[]> raw;

    @BeforeAll
    static void topics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", Infrastructure.KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(Topics.ORDER_CREATED, 3, (short) 1),
                    new NewTopic(Topics.ORDER_CANCELLED, 3, (short) 1),
                    new NewTopic(Topics.ORDER_CONFIRMED, 3, (short) 1))).all().get();
        }
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Infrastructure.KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        p.put("schema.registry.url", Infrastructure.schemaRegistryUrl());
        avro = new KafkaProducer<>(p);
        Properties r = new Properties();
        r.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Infrastructure.KAFKA.getBootstrapServers());
        r.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        r.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        raw = new KafkaProducer<>(r);
    }

    @AfterAll
    static void close() {
        avro.close();
        raw.close();
    }

    void send(String topic, String orderId, Object event) throws Exception {
        avro.send(new ProducerRecord<>(topic, orderId, event)).get();
    }

    double count(String name) {
        return meters.counter(name).count();
    }

    /** Waits for a record with this key on the dead-letter topic. */
    ConsumerRecord<String, byte[]> awaitDeadLetter(String key) {
        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infrastructure.KAFKA.getBootstrapServers());
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(c)) {
            consumer.subscribe(List.of(DLQ));
            List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(rec -> {
                    if (key.equals(rec.key())) {
                        found.add(rec);
                    }
                });
                return !found.isEmpty();
            });
            return found.get(0);
        }
    }

    static String header(ConsumerRecord<?, ?> rec, String name) {
        var h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    boolean dlqHas(String key) {
        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infrastructure.KAFKA.getBootstrapServers());
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-check-" + UUID.randomUUID());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(c)) {
            consumer.subscribe(List.of(DLQ));
            for (int i = 0; i < 6; i++) {
                for (var rec : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(rec.key())) {
                        return true;
                    }
                }
            }
            return false;
        }
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
        raw.send(new ProducerRecord<>(Topics.ORDER_CREATED, poisonKey, "this is not avro".getBytes(StandardCharsets.UTF_8))).get();

        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(poisonKey);

        assertThat(new String(dead.value(), StandardCharsets.UTF_8)).isEqualTo("this is not avro");   // forwarded untouched
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(Topics.ORDER_CREATED);
        assertThat(header(dead, "kafka_dlt-exception-fqcn")).contains("DeserializationException");
        assertThat(count("oms.query.events.deliveries")).isEqualTo(deliveries);     // never reached the listener: no retries

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
        assertThat(new String(dead.value(), StandardCharsets.UTF_8)).contains(id);       // readable JSON of the event
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
            assertThat(new String(record.value(), StandardCharsets.UTF_8)).contains(id);
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
