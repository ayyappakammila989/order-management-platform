package com.retail.oms.gateway.application;

import com.retail.oms.kafka.AvroEventDecoder;
import com.retail.oms.kafka.SchemaRegistryUnavailableException;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;

/** Avro -> Confluent wire format bytes (magic byte + schema id), like the outbox of the other services. */
public class AvroBytes {

    private final KafkaAvroSerializer serializer = new KafkaAvroSerializer();

    public AvroBytes(String schemaRegistryUrl, Duration timeout) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("schema.registry.url", schemaRegistryUrl);
        cfg.put("schema.registry.http.connect.timeout.ms", (int) timeout.toMillis());
        cfg.put("schema.registry.http.read.timeout.ms", (int) timeout.toMillis());
        cfg.put("schema.registry.max.retries", 0);
        serializer.configure(cfg, false);
    }

    public byte[] serialize(String topic, SpecificRecord message) {
        try {
            return serializer.serialize(topic, message);
        } catch (RuntimeException e) {
            if (AvroEventDecoder.isRegistryLookupFailure(e)) {
                throw new SchemaRegistryUnavailableException("Cannot register the schema of "
                        + message.getSchema().getName() + ": " + e.getMessage(), e);
            }
            throw e;
        }
    }
}
