package com.retail.oms.order.infrastructure.messaging;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Serializes events exactly as a Kafka producer with KafkaAvroSerializer would: magic byte 0, the 4-byte schema id
 * from the Schema Registry, then Avro binary. Debezium then copies these bytes to Kafka unchanged, so consumers use
 * the normal KafkaAvroDeserializer.
 *
 * <p>The schema is registered (or looked up) on first use per topic and cached afterwards, so the Schema Registry
 * is only called once per event type per process.
 */
@Component
public class AvroEventSerializer {

    private final KafkaAvroSerializer serializer = new KafkaAvroSerializer();

    public AvroEventSerializer(@Value("${oms.order.schema-registry-url}") String schemaRegistryUrl) {
        // subject = <topic>-value (TopicNameStrategy), the same subject a normal producer on that topic would use
        serializer.configure(Map.of("schema.registry.url", schemaRegistryUrl, "auto.register.schemas", true), false);
    }

    public byte[] serialize(String topic, SpecificRecord event) {
        return serializer.serialize(topic, event);
    }

    @PreDestroy
    void close() {
        serializer.close();
    }
}
