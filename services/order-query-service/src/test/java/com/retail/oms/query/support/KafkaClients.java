package com.retail.oms.query.support;

import static org.awaitility.Awaitility.await;

import com.retail.oms.events.Topics;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
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
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Test producers (Avro and raw bytes) and dead-letter topic readers, shared by the Kafka ITs. */
public final class KafkaClients {

    public static final String DLQ = "oms.order-query.dlq";

    private static KafkaProducer<String, Object> avro;
    private static KafkaProducer<String, byte[]> raw;

    private KafkaClients() {
    }

    /** Creates the order topics once (3 partitions, as in Compose) and the producers. */
    public static synchronized void init() throws Exception {
        if (avro != null) {
            return;
        }
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", Infrastructure.KAFKA.getBootstrapServers()))) {
            for (String topic : List.of(Topics.ORDER_CREATED, Topics.ORDER_CANCELLED, Topics.ORDER_CONFIRMED)) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
            }
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

    public static void sendAvro(String topic, String key, Object event) throws Exception {
        avro.send(new ProducerRecord<>(topic, key, event)).get();
    }

    public static void sendRaw(String topic, String key, byte[] value) throws Exception {
        raw.send(new ProducerRecord<>(topic, key, value)).get();
    }

    /** Waits for a record with this key on the dead-letter topic. */
    public static ConsumerRecord<String, byte[]> awaitDeadLetter(String key, Duration atMost) {
        try (KafkaConsumer<String, byte[]> consumer = dlqConsumer("dlq-reader-")) {
            List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
            await().atMost(atMost).until(() -> {
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

    public static ConsumerRecord<String, byte[]> awaitDeadLetter(String key) {
        return awaitDeadLetter(key, Duration.ofSeconds(60));
    }

    /** Reads the whole DLQ (a few polls) and tells whether a record with this key is in it. */
    public static boolean dlqHas(String key) {
        try (KafkaConsumer<String, byte[]> consumer = dlqConsumer("dlq-check-")) {
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

    public static String header(ConsumerRecord<?, ?> rec, String name) {
        var h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static KafkaConsumer<String, byte[]> dlqConsumer(String groupPrefix) {
        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infrastructure.KAFKA.getBootstrapServers());
        c.put(ConsumerConfig.GROUP_ID_CONFIG, groupPrefix + UUID.randomUUID());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        var consumer = new KafkaConsumer<String, byte[]>(c);
        consumer.subscribe(List.of(DLQ));
        return consumer;
    }
}
