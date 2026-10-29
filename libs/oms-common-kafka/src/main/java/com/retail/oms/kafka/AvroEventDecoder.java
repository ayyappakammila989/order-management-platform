package com.retail.oms.kafka;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.header.Headers;

/**
 * Decodes Confluent-framed Avro inside the listener instead of in the consumer's deserializer.
 * <p>
 * Why not {@code ErrorHandlingDeserializer}: it turns every failure into a {@code DeserializationException}, so
 * "the registry is down for a few seconds" and "these bytes are garbage" look the same and both go straight to the
 * dead-letter topic. Here the two are told apart, and the listener receives the raw bytes, so whatever is
 * dead-lettered is exactly what arrived and can be replayed unchanged (see {@link DlqReplay}).
 */
public class AvroEventDecoder {

    private final KafkaAvroDeserializer deserializer;

    /** @param timeout connect and read timeout of each registry call; a hung registry must not block the consumer for minutes */
    public AvroEventDecoder(String schemaRegistryUrl, Duration timeout) {
        this(new KafkaAvroDeserializer(), config(schemaRegistryUrl, timeout));
    }

    /** For tests: a deserializer with a given (e.g. mock) registry client. */
    public AvroEventDecoder(SchemaRegistryClient client, String schemaRegistryUrl) {
        this(new KafkaAvroDeserializer(client), config(schemaRegistryUrl, Duration.ofSeconds(5)));
    }

    private AvroEventDecoder(KafkaAvroDeserializer deserializer, Map<String, Object> config) {
        this.deserializer = deserializer;
        this.deserializer.configure(config, false);
    }

    private static Map<String, Object> config(String url, Duration timeout) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("schema.registry.url", url);
        cfg.put("specific.avro.reader", true);
        cfg.put("schema.registry.http.connect.timeout.ms", (int) timeout.toMillis());
        cfg.put("schema.registry.http.read.timeout.ms", (int) timeout.toMillis());
        // one retry policy, ours (backoff in the error handler); the client's own 3 retries would multiply it
        cfg.put("schema.registry.max.retries", 0);
        return cfg;
    }

    /**
     * @return the specific Avro record
     * @throws SchemaRegistryUnavailableException the writer schema could not be fetched (retry)
     * @throws PoisonPillException the bytes can never be decoded (do not retry)
     */
    public Object decode(String topic, Headers headers, byte[] value) {
        if (value == null) {
            throw new PoisonPillException("Record on " + topic + " has no value", null);
        }
        try {
            return deserializer.deserialize(topic, headers, value);
        } catch (RuntimeException e) {
            if (isRegistryLookupFailure(e)) {
                throw new SchemaRegistryUnavailableException("Schema Registry lookup failed for a record on " + topic
                        + ": " + describe(e), e);
            }
            throw new PoisonPillException("Cannot decode a record on " + topic + ": " + describe(e), e);
        }
    }

    /** "outer message (root cause message)": the root cause is what says "Schema 1 not found" or "Connection refused". */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? e.getMessage() : e.getMessage() + " (" + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
    }

    /**
     * What the Confluent 7.9 deserializer throws when it cannot get the schema: a {@link RestClientException}
     * (e.g. 404 "Schema 1 not found", 5xx) or a network error, sometimes mapped to a Kafka {@link RetriableException}
     * (TimeoutException for 408/5xx/read timeouts). Decoding errors carry other causes, e.g. EOFException or
     * AvroRuntimeException for corrupt bytes, or none at all ("Unknown magic byte!").
     */
    public static boolean isRegistryLookupFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RestClientException || t instanceof RetriableException
                    || t instanceof SocketException || t instanceof UnknownHostException
                    || t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
