package com.retail.oms.kafka.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.retail.oms.events.inventory.ReleaseStockCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/** The outbox relay against real PostgreSQL and Kafka: order, at-least-once on a Kafka outage, SKIP LOCKED with two relays. */
class OutboxRelayIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static KafkaTemplate<String, byte[]> kafka;
    static Outbox outbox;

    @BeforeAll
    static void start() throws Exception {
        Startables.deepStart(POSTGRES, KAFKA).join();
        var ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("""
                CREATE TABLE outbox_event (
                  id UUID PRIMARY KEY, topic VARCHAR(200) NOT NULL, message_key VARCHAR(200) NOT NULL,
                  event_type VARCHAR(100) NOT NULL, payload BYTEA NOT NULL, correlation_id VARCHAR(100),
                  created_at TIMESTAMPTZ NOT NULL, published_at TIMESTAMPTZ, attempts INT NOT NULL DEFAULT 0,
                  last_error VARCHAR(500));
                CREATE TABLE processed_message (consumer VARCHAR(100) NOT NULL, message_id VARCHAR(100) NOT NULL,
                  processed_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (consumer, message_id));""");
        kafka = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                // fail fast (what the services use too): the relay just tries again on its next run
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 4000)));
        outbox = new Outbox(jdbc, "mock://outbox-it", Duration.ofSeconds(2), Clock.systemUTC());
    }

    String topic;

    @BeforeEach
    void freshTopic() throws Exception {
        topic = "outbox-it-" + UUID.randomUUID();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
        }
        jdbc.update("DELETE FROM outbox_event");
    }

    OutboxRelay relay(int batchSize) {
        return new OutboxRelay(jdbc, tx, kafka, batchSize, Duration.ofSeconds(2), Clock.systemUTC(), new SimpleMeterRegistry());
    }

    String add(String orderId) {
        String id = UUID.randomUUID().toString();
        var cmd = ReleaseStockCommand.newBuilder().setCommandId(id).setOccurredAt(Instant.now()).setSagaId("saga-1")
                .setOrderId(orderId).setReason("test").build();
        tx.executeWithoutResult(s -> outbox.add(topic, orderId, id, cmd, "corr-" + id));
        return id;
    }

    List<ConsumerRecord<String, byte[]>> readAll(int expected) {
        var props = Map.<String, Object>of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        List<ConsumerRecord<String, byte[]>> out = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(300)).forEach(out::add);
                return out.size() >= expected;
            });
            consumer.poll(Duration.ofMillis(1000)).forEach(out::add);     // anything extra (duplicates) as well
        }
        return out;
    }

    static String header(ConsumerRecord<?, ?> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @Test
    void sendsRowsInOrderWithHeadersAndMarksThemSent() {
        List<String> ids = List.of(add("order-A"), add("order-A"), add("order-A"));

        assertThat(relay(10).publishBatch()).isEqualTo(3);
        assertThat(relay(10).publishBatch()).isZero();                       // nothing left

        var records = readAll(3);
        assertThat(records).hasSize(3);
        assertThat(records).extracting(r -> header(r, "id")).containsExactlyElementsOf(ids);   // same key: same order
        assertThat(header(records.get(0), "eventType")).isEqualTo("ReleaseStockCommand");
        assertThat(header(records.get(0), "correlationId")).isEqualTo("corr-" + ids.get(0));
        assertThat(records.get(0).value()[0]).isEqualTo((byte) 0);           // Confluent framing: magic byte
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void kafkaOutageLeavesRowsUnsentAndTheyGoOutWhenKafkaIsBack() {
        String first = add("order-B");
        add("order-B");
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            assertThat(relay(10).publishBatch()).isZero();                   // 2 s send timeout, then stop the batch
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        Map<String, Object> failed = jdbc.queryForMap("SELECT attempts, last_error, published_at FROM outbox_event WHERE id = ?::uuid", first);
        assertThat(failed.get("attempts")).isEqualTo(1);
        assertThat((String) failed.get("last_error")).contains("Timeout");
        assertThat(failed.get("published_at")).isNull();

        await().atMost(Duration.ofSeconds(30)).until(() -> relay(10).publishBatch() == 0
                && jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class) == 0);

        // at-least-once: the timed-out send may still have been delivered by the producer, so a duplicate is allowed
        var ids = readAll(2).stream().map(r -> header(r, "id")).toList();
        assertThat(Set.copyOf(ids)).hasSize(2);
        assertThat(ids.size()).isBetween(2, 3);
    }

    @Test
    void twoRelaysTogetherSendEveryRowExactlyOnce() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(add("order-" + (i % 17)));
        }
        Map<String, Integer> sentBy = new ConcurrentHashMap<>();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> runs = new ArrayList<>();
            for (String name : List.of("relay-1", "relay-2")) {
                OutboxRelay relay = relay(10);
                runs.add(pool.submit(() -> {
                    start.await();
                    int n;
                    while ((n = relay.publishBatch()) > 0) {
                        sentBy.merge(name, n, Integer::sum);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var run : runs) {
                run.get();
            }
        }

        var received = readAll(200).stream().map(r -> header(r, "id")).toList();
        assertThat(received).hasSize(200).doesNotHaveDuplicates();
        assertThat(received).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(sentBy.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(200);
        assertThat(sentBy).containsKeys("relay-1", "relay-2");             // both did work: SKIP LOCKED, not waiting
    }

    @Test
    void processedMessagesReportsTheFirstDeliveryOnly() {
        var processed = new ProcessedMessages(jdbc);
        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 3; i++) {
            tx.executeWithoutResult(s -> results.add(processed.markProcessed("it-consumer", "msg-1")));
        }
        tx.executeWithoutResult(s -> results.add(processed.markProcessed("other-consumer", "msg-1")));

        assertThat(results).containsExactly(true, false, false, true);   // per consumer
    }

    @Test
    void oldSentRowsAreDeleted() {
        add("order-C");
        relay(10).publishBatch();
        jdbc.update("UPDATE outbox_event SET published_at = now() - interval '8 days'");
        add("order-C");                                                 // not sent yet: must stay

        assertThat(relay(10).deletePublishedBefore(Duration.ofDays(7))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isEqualTo(1);
    }
}
