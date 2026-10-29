package com.retail.oms.gateway.support;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
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

/** Plays payment-service in the tests: sends gateway commands and reads results, webhook events and dead letters. */
public final class KafkaTestClient implements AutoCloseable {

    private final KafkaProducer<String, Object> avro;
    private final KafkaProducer<String, byte[]> raw;

    public KafkaTestClient() {
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

    public void send(String topic, String key, Object message) throws Exception {
        avro.send(new ProducerRecord<>(topic, key, message)).get();
    }

    /** With the correlationId header the orchestrator's outbox relay sets. */
    public void send(String topic, String key, Object message, String correlationId) throws Exception {
        var record = new ProducerRecord<String, Object>(topic, key, message);
        record.headers().add("correlationId", correlationId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        avro.send(record).get();
    }

    public void sendRaw(String topic, String key, byte[] value) throws Exception {
        raw.send(new ProducerRecord<>(topic, key, value)).get();
    }

    /** A reader positioned at the start of the topics, to collect what arrives for one key. */
    public Reader reader(String... topics) {
        return new Reader(true, topics);
    }

    /** Same, but values as raw bytes (dead-letter topic). */
    public Reader rawReader(String... topics) {
        return new Reader(false, topics);
    }

    @Override
    public void close() {
        avro.close();
        raw.close();
    }

    public static final class Reader implements AutoCloseable {

        private final KafkaConsumer<String, Object> consumer;
        private final List<ConsumerRecord<String, Object>> seen = new ArrayList<>();

        Reader(boolean avroValues, String... topics) {
            Properties c = new Properties();
            c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infrastructure.KAFKA.getBootstrapServers());
            c.put(ConsumerConfig.GROUP_ID_CONFIG, "it-reader-" + UUID.randomUUID());
            c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, avroValues ? KafkaAvroDeserializer.class : ByteArrayDeserializer.class);
            c.put("schema.registry.url", Infrastructure.schemaRegistryUrl());
            c.put("specific.avro.reader", true);
            consumer = new KafkaConsumer<>(c);
            consumer.subscribe(List.of(topics));
        }

        /** Polls until at least {@code n} records with this key have arrived (on any of the topics). */
        public List<ConsumerRecord<String, Object>> await(String key, int n, Duration atMost) {
            return awaitMatching(r -> key.equals(r.key()), n, atMost);
        }

        public List<ConsumerRecord<String, Object>> awaitMatching(Predicate<ConsumerRecord<String, Object>> match, int n,
                Duration atMost) {
            org.awaitility.Awaitility.await().atMost(atMost).until(() -> {
                consumer.poll(Duration.ofMillis(300)).forEach(seen::add);
                return seen.stream().filter(match).count() >= n;
            });
            return seen.stream().filter(match).toList();
        }

        /** Everything with this key seen after polling {@code quiet} more (to prove nothing else arrives). */
        public List<ConsumerRecord<String, Object>> settle(String key, Duration quiet) {
            long end = System.nanoTime() + quiet.toNanos();
            while (System.nanoTime() < end) {
                consumer.poll(Duration.ofMillis(300)).forEach(seen::add);
            }
            return seen.stream().filter(r -> key.equals(r.key())).toList();
        }

        @Override
        public void close() {
            consumer.close();
        }
    }
}
