package com.retail.oms.kafka.outbox;

import com.retail.oms.kafka.AvroEventDecoder;
import com.retail.oms.kafka.SchemaRegistryUnavailableException;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox (architecture 5): an event or command is written to {@code outbox_event} in the SAME database
 * transaction as the business change, so both happen or neither does. {@link OutboxRelay} sends it to Kafka later.
 * <p>
 * The payload is already in Confluent wire format (magic byte + schema id + Avro), like order-service's outbox, so
 * the {@code .avsc} stays the only contract. Table (each service's Flyway script):
 * <pre>
 * outbox_event(id UUID PK, topic, message_key, event_type, payload BYTEA, correlation_id,
 *              created_at, published_at NULL until sent, attempts, last_error)
 * </pre>
 */
public class Outbox {

    private final JdbcTemplate jdbc;
    private final KafkaAvroSerializer serializer;
    private final Clock clock;

    public Outbox(JdbcTemplate jdbc, String schemaRegistryUrl, Duration registryTimeout, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.serializer = new KafkaAvroSerializer();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("schema.registry.url", schemaRegistryUrl);
        cfg.put("schema.registry.http.connect.timeout.ms", (int) registryTimeout.toMillis());
        cfg.put("schema.registry.http.read.timeout.ms", (int) registryTimeout.toMillis());
        cfg.put("schema.registry.max.retries", 0);
        serializer.configure(cfg, false);
    }

    /**
     * Adds one message to the outbox; must run inside the caller's transaction.
     *
     * @param id the message id (event id or command id); also sent as the Kafka header {@code id}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String topic, String key, String id, SpecificRecord message, String correlationId) {
        byte[] payload;
        try {
            payload = serializer.serialize(topic, message);
        } catch (RuntimeException e) {
            if (AvroEventDecoder.isRegistryLookupFailure(e)) {
                // retried with the registry backoff by the consumer error handler, like a failed lookup when reading
                throw new SchemaRegistryUnavailableException("Cannot register/look up the schema of "
                        + message.getSchema().getName() + " for " + topic + ": " + e.getMessage(), e);
            }
            throw e;
        }
        jdbc.update("""
                INSERT INTO outbox_event (id, topic, message_key, event_type, payload, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                UUID.fromString(id), topic, key, message.getSchema().getName(), payload, correlationId,
                java.sql.Timestamp.from(Instant.now(clock)));
    }
}
