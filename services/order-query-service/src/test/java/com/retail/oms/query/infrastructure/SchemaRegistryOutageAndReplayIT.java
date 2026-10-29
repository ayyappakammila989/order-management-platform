package com.retail.oms.query.infrastructure;

import static com.retail.oms.query.support.Events.T0;
import static com.retail.oms.query.support.KafkaClients.DLQ;
import static com.retail.oms.query.support.KafkaClients.awaitDeadLetter;
import static com.retail.oms.query.support.KafkaClients.dlqHas;
import static com.retail.oms.query.support.KafkaClients.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.retail.oms.events.Topics;
import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.kafka.DlqReplay;
import com.retail.oms.query.application.OrderIndex;
import com.retail.oms.query.support.AbstractQueryIT;
import com.retail.oms.query.support.Events;
import com.retail.oms.query.support.Infrastructure;
import com.retail.oms.query.support.KafkaClients;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 2 follow-up: a Schema Registry problem is an outage, not a bad message. It is retried with its own backoff
 * (in the tests 1 + 5 attempts, waits 0.3/0.6/1.2/2.4/4.8 s), and only then dead-lettered; the dead-letter record
 * holds the original bytes and {@link DlqReplay} puts it back. Real Kafka, registry, Elasticsearch and Redis;
 * "registry down" is the registry container being paused.
 * <p>
 * The consumer caches schemas by id, so each test writes its record with a schema id the consumer has never seen:
 * a compatible variant of OrderCreatedEvent (one extra optional field) registered just for that test.
 */
class SchemaRegistryOutageAndReplayIT extends AbstractQueryIT {

    @Autowired OrderIndex index;
    @Autowired MeterRegistry meters;

    @BeforeAll
    static void topics() throws Exception {
        KafkaClients.init();
    }

    double count(String name) {
        return meters.counter(name).count();
    }

    /** Registers OrderCreatedEvent plus an optional field {@code extraField} under the topic's subject. */
    static int registerVariant(String extraField) throws Exception {
        Schema base = OrderCreatedEvent.getClassSchema();
        List<Schema.Field> fields = new ArrayList<>();
        base.getFields().forEach(f -> fields.add(new Schema.Field(f, f.schema())));
        fields.add(new Schema.Field(extraField, Schema.createUnion(Schema.create(Schema.Type.NULL),
                Schema.create(Schema.Type.STRING)), null, Schema.Field.NULL_DEFAULT_VALUE));
        Schema variant = Schema.createRecord(base.getName(), base.getDoc(), base.getNamespace(), false, fields);
        try (var client = new CachedSchemaRegistryClient(Infrastructure.schemaRegistryUrl(), 10)) {
            return client.register(Topics.ORDER_CREATED + "-value", new AvroSchema(variant));
        }
    }

    /**
     * Confluent framing (magic 0 + schema id) around the Avro body of {@code event}, plus one 0 byte: the extra
     * optional field is last, and 0 is the union branch "null". So the bytes are a valid message for the variant.
     */
    static byte[] asVariant(int schemaId, OrderCreatedEvent event) {
        byte[] body;
        try (var serializer = new KafkaAvroSerializer()) {
            serializer.configure(Map.of("schema.registry.url", Infrastructure.schemaRegistryUrl()), false);
            byte[] framed = serializer.serialize(Topics.ORDER_CREATED, event);
            body = Arrays.copyOfRange(framed, 5, framed.length);
        }
        return ByteBuffer.allocate(5 + body.length + 1).put((byte) 0).putInt(schemaId).put(body).put((byte) 0).array();
    }

    static void pauseRegistry() {
        var sr = Infrastructure.SCHEMA_REGISTRY;
        sr.getDockerClient().pauseContainerCmd(sr.getContainerId()).exec();
    }

    static void unpauseRegistry() {
        var sr = Infrastructure.SCHEMA_REGISTRY;
        sr.getDockerClient().unpauseContainerCmd(sr.getContainerId()).exec();
    }

    static String replay(String... selection) {
        var out = new ByteArrayOutputStream();
        String[] args = new String[4 + selection.length];
        args[0] = "--bootstrap";
        args[1] = Infrastructure.KAFKA.getBootstrapServers();
        args[2] = "--dlq";
        args[3] = DLQ;
        System.arraycopy(selection, 0, args, 4, selection.length);
        DlqReplay.run(args, new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void shortRegistryOutageIsRetriedAndTheEventArrivesWhenTheRegistryIsBack() throws Exception {
        String id = UUID.randomUUID().toString();
        byte[] value = asVariant(registerVariant("itNoteShortOutage"), Events.created(id, "cust-1", T0));
        double registryErrors = count("oms.query.events.registry_errors");

        pauseRegistry();
        try {
            KafkaClients.sendRaw(Topics.ORDER_CREATED, id, value);
            await().atMost(Duration.ofSeconds(30))      // first attempt failed and at least one retry after a backoff
                    .until(() -> count("oms.query.events.registry_errors") - registryErrors >= 2);
        } finally {
            unpauseRegistry();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(index.find(id)).isPresent());
        assertThat(dlqHas(id)).isFalse();
    }

    @Test
    void schemaThatTheRegistryDoesNotKnowIsRetriedLikeAnOutageBeforeItIsDeadLettered() throws Exception {
        // what happened in the step 2B smoke run: "Schema 1 not found" from a registry that was still loading
        String id = UUID.randomUUID().toString();
        byte[] value = asVariant(4_711_000, Events.created(id, "cust-1", T0));     // an id that does not exist
        double registryErrors = count("oms.query.events.registry_errors");

        KafkaClients.sendRaw(Topics.ORDER_CREATED, id, value);
        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(id, Duration.ofSeconds(60));

        assertThat(count("oms.query.events.registry_errors") - registryErrors).isEqualTo(6);   // 1 + 5 retries
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("SchemaRegistryUnavailableException");
        assertThat(header(dead, "kafka_dlt-exception-message")).containsIgnoringCase("not found");
        assertThat(dead.value()).isEqualTo(value);
    }

    @Test
    void registryDownLongerThanAllRetriesIsDeadLetteredAndReplayedWhenItIsBack() throws Exception {
        String id = UUID.randomUUID().toString();
        byte[] value = asVariant(registerVariant("itNoteLongOutage"), Events.created(id, "cust-1", T0));
        double registryErrors = count("oms.query.events.registry_errors");
        double duplicates = count("oms.query.events.duplicate");
        ConsumerRecord<String, byte[]> dead;
        Duration took;

        pauseRegistry();
        long start = System.nanoTime();
        try {
            KafkaClients.sendRaw(Topics.ORDER_CREATED, id, value);
            dead = awaitDeadLetter(id, Duration.ofSeconds(90));
            took = Duration.ofNanos(System.nanoTime() - start);
        } finally {
            unpauseRegistry();
        }

        assertThat(count("oms.query.events.registry_errors") - registryErrors).isEqualTo(6);   // 1 + 5 retries
        assertThat(took).isGreaterThan(Duration.ofMillis(9_300));     // 0.3 + 0.6 + 1.2 + 2.4 + 4.8 s of backoff
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("SchemaRegistryUnavailableException");
        assertThat(dead.value()).isEqualTo(value);                    // exactly the bytes that arrived
        assertThat(index.find(id)).isEmpty();

        String output = replay("--key", id);                          // registry is back: put it on its topic again

        assertThat(output).contains("replayed").contains("-> " + Topics.ORDER_CREATED + "-");
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(index.find(id)).isPresent());

        replay("--key", id);                                          // replaying twice is harmless
        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(count("oms.query.events.duplicate") - duplicates).isEqualTo(1));
        assertThat(index.find(id).orElseThrow().statusHistory()).hasSize(1);
    }

    @Test
    void eventDeadLetteredDuringAnElasticsearchOutageIsReplayedByOffset() throws Exception {
        String id = UUID.randomUUID().toString();
        var es = Infrastructure.ELASTICSEARCH;
        ConsumerRecord<String, byte[]> dead;
        es.getDockerClient().pauseContainerCmd(es.getContainerId()).exec();
        try {
            KafkaClients.sendAvro(Topics.ORDER_CREATED, id, Events.created(id, "cust-1", T0));
            dead = awaitDeadLetter(id);
        } finally {
            es.getDockerClient().unpauseContainerCmd(es.getContainerId()).exec();
        }
        String position = dead.partition() + ":" + dead.offset();

        String listed = replay("--list");
        assertThat(listed).contains(position + " key=" + id + " from=" + Topics.ORDER_CREATED);

        String dryRun = replay("--offset", position, "--dry-run");
        assertThat(dryRun).contains("would replay " + position);

        double applied = count("oms.query.events.applied");
        String output = replay("--offset", position);
        assertThat(output).contains("replayed " + position);
        // processed again, not skipped as a duplicate: the failed attempt never marked the event id as handled
        // (even if Elasticsearch applied the timed-out write after waking up, which the idempotent script absorbs)
        await().atMost(Duration.ofSeconds(60)).until(() -> count("oms.query.events.applied") - applied >= 1);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var doc = index.find(id).orElseThrow();
            assertThat(doc.status()).isEqualTo("PENDING");
            assertThat(doc.lines()).hasSize(1);
        });
    }
}
