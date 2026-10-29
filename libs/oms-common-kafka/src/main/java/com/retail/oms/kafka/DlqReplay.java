package com.retail.oms.kafka;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/**
 * Puts dead-lettered records back on the topic they came from (header {@code kafka_dlt-original-topic}), with the
 * same key, the same value bytes and the original headers. The {@code kafka_dlt-*} headers are dropped and
 * {@code oms-replayed-from} is added. Run it through {@code scripts/replay-dlq.sh}.
 * <p>
 * Safe to run twice: the consumers are idempotent (event id + idempotent writes), so a replayed duplicate is ignored.
 * The DLQ itself is not changed; Kafka cannot delete single records.
 * <pre>
 *   --bootstrap localhost:9092 --dlq oms.order-query.dlq --list
 *   --bootstrap localhost:9092 --dlq oms.order-query.dlq --offset 0:5 [--offset 0:7] [--to topic] [--dry-run]
 *   --bootstrap localhost:9092 --dlq oms.order-query.dlq --key &lt;record key&gt;
 *   --bootstrap localhost:9092 --dlq oms.order-query.dlq --all
 * </pre>
 */
public final class DlqReplay {

    public static final String ORIGINAL_TOPIC = "kafka_dlt-original-topic";
    public static final String REPLAYED_FROM = "oms-replayed-from";

    private DlqReplay() {
    }

    public static void main(String[] args) {
        int replayed;
        try {
            replayed = run(args, System.out);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("usage: --bootstrap HOST:PORT --dlq TOPIC (--list | --offset P:O ... | --key K | --all)"
                    + " [--to TOPIC] [--dry-run]");
            System.exit(2);
            return;
        }
        System.exit(replayed < 0 ? 1 : 0);
    }

    /** @return number of records replayed (or listed), or -1 if a selection matched nothing */
    public static int run(String[] args, PrintStream out) {
        Options o = Options.parse(args);
        List<ConsumerRecord<byte[], byte[]>> records = readAll(o.bootstrap, o.dlq);
        if (o.list) {
            out.printf("%d record(s) in %s%n", records.size(), o.dlq);
            records.forEach(r -> out.println("  " + describe(r)));
            return records.size();
        }
        List<ConsumerRecord<byte[], byte[]>> selected = records.stream().filter(o::matches).toList();
        if (selected.isEmpty()) {
            out.println("No record in " + o.dlq + " matches the selection");
            return -1;
        }
        if (o.dryRun) {
            selected.forEach(r -> out.println("would replay " + describe(r) + " -> " + target(r, o.to)));
            return selected.size();
        }
        try (KafkaProducer<byte[], byte[]> producer = producer(o.bootstrap)) {
            for (var r : selected) {
                String topic = target(r, o.to);
                var headers = new RecordHeaders();
                for (Header h : r.headers()) {
                    if (!h.key().startsWith("kafka_dlt-")) {
                        headers.add(h);
                    }
                }
                headers.add(REPLAYED_FROM, (r.topic() + "-" + r.partition() + "@" + r.offset()).getBytes(StandardCharsets.UTF_8));
                RecordMetadata meta = producer.send(new ProducerRecord<>(topic, null, r.key(), r.value(), headers)).get();
                out.printf("replayed %s -> %s-%d@%d%n", describe(r), meta.topic(), meta.partition(), meta.offset());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Replay failed: " + e.getMessage(), e);
        }
        return selected.size();
    }

    private static String target(ConsumerRecord<byte[], byte[]> r, String override) {
        if (override != null) {
            return override;
        }
        String original = header(r, ORIGINAL_TOPIC);
        if (original == null) {
            throw new IllegalArgumentException("Record " + r.partition() + ":" + r.offset()
                    + " has no " + ORIGINAL_TOPIC + " header; give the target with --to");
        }
        return original;
    }

    private static String describe(ConsumerRecord<byte[], byte[]> r) {
        String reason = header(r, "kafka_dlt-exception-cause-fqcn");
        if (reason == null) {
            reason = header(r, "kafka_dlt-exception-fqcn");
        }
        String message = header(r, "kafka_dlt-exception-message");
        // Spring's wrapper says "Listener method '...' threw exception; <the real message>": keep the real part
        int cut = message == null ? -1 : message.indexOf("threw exception; ");
        if (cut >= 0) {
            message = message.substring(cut + "threw exception; ".length());
        }
        if (message != null && message.length() > 120) {
            message = message.substring(0, 120) + "...";
        }
        return String.format("%d:%d key=%s from=%s-%s@%s %s(%s)%s", r.partition(), r.offset(),
                r.key() == null ? null : new String(r.key(), StandardCharsets.UTF_8),
                header(r, ORIGINAL_TOPIC), header(r, "kafka_dlt-original-partition"),
                header(r, "kafka_dlt-original-offset"), reason, message,
                header(r, REPLAYED_FROM) == null ? "" : " (itself a replay of " + header(r, REPLAYED_FROM) + ")");
    }

    static String header(ConsumerRecord<?, ?> r, String name) {
        Header h = r.headers().lastHeader(name);
        if (h == null) {
            return null;
        }
        byte[] v = h.value();
        // Spring writes partition and offset headers as binary int/long
        if (name.equals("kafka_dlt-original-partition") && v.length == 4) {
            return String.valueOf(java.nio.ByteBuffer.wrap(v).getInt());
        }
        if (name.equals("kafka_dlt-original-offset") && v.length == 8) {
            return String.valueOf(java.nio.ByteBuffer.wrap(v).getLong());
        }
        return new String(v, StandardCharsets.UTF_8);
    }

    /** Reads the DLQ from the start to its current end, without a consumer group (nothing is committed). */
    static List<ConsumerRecord<byte[], byte[]>> readAll(String bootstrap, String dlq) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<byte[], byte[]>> out = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(p)) {
            var infos = consumer.partitionsFor(dlq, Duration.ofSeconds(10));
            if (infos == null || infos.isEmpty()) {
                throw new IllegalArgumentException("Topic " + dlq + " does not exist");
            }
            List<TopicPartition> parts = infos.stream().map(i -> new TopicPartition(dlq, i.partition())).toList();
            consumer.assign(parts);
            consumer.seekToBeginning(parts);
            Map<TopicPartition, Long> end = consumer.endOffsets(parts);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (parts.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Timed out reading " + dlq);
                }
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (r.offset() < end.get(new TopicPartition(r.topic(), r.partition()))) {
                        out.add(r);
                    }
                });
            }
        }
        return out;
    }

    private static KafkaProducer<byte[], byte[]> producer(String bootstrap) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaProducer<>(p);
    }

    private record Options(String bootstrap, String dlq, boolean list, boolean all, Set<String> offsets, String key,
                           String to, boolean dryRun) {

        static Options parse(String[] args) {
            String bootstrap = null;
            String dlq = null;
            String key = null;
            String to = null;
            boolean list = false;
            boolean all = false;
            boolean dryRun = false;
            Set<String> offsets = new HashSet<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--bootstrap" -> bootstrap = value(args, ++i);
                    case "--dlq" -> dlq = value(args, ++i);
                    case "--key" -> key = value(args, ++i);
                    case "--to" -> to = value(args, ++i);
                    case "--offset" -> {
                        String v = value(args, ++i);
                        if (!v.matches("\\d+:\\d+")) {
                            throw new IllegalArgumentException("--offset must be PARTITION:OFFSET, e.g. 0:5");
                        }
                        offsets.add(v);
                    }
                    case "--list" -> list = true;
                    case "--all" -> all = true;
                    case "--dry-run" -> dryRun = true;
                    default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
                }
            }
            if (bootstrap == null || dlq == null) {
                throw new IllegalArgumentException("--bootstrap and --dlq are required");
            }
            int selections = (list ? 1 : 0) + (all ? 1 : 0) + (offsets.isEmpty() ? 0 : 1) + (key == null ? 0 : 1);
            if (selections != 1) {
                throw new IllegalArgumentException("Give exactly one of --list, --all, --offset, --key");
            }
            return new Options(bootstrap, dlq, list, all, offsets, key, to, dryRun);
        }

        private static String value(String[] args, int i) {
            if (i >= args.length) {
                throw new IllegalArgumentException("Missing value for " + args[i - 1]);
            }
            return args[i];
        }

        boolean matches(ConsumerRecord<byte[], byte[]> r) {
            if (all) {
                return true;
            }
            if (key != null) {
                return r.key() != null && key.equals(new String(r.key(), StandardCharsets.UTF_8));
            }
            return offsets.contains(r.partition() + ":" + r.offset());
        }
    }
}
