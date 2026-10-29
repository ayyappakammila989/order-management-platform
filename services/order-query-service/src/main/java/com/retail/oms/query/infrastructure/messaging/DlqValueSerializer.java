package com.retail.oms.query.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Values on the dead-letter topic are readable text. A message that could not even be deserialized is forwarded as the
 * raw bytes it arrived with; an event that was understood but could not be indexed is written as its JSON form.
 * (Avro on the DLQ would put events of different types under one subject and clash in the Schema Registry.)
 */
public class DlqValueSerializer implements Serializer<Object> {

    @Override
    public byte[] serialize(String topic, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] raw) {
            return raw;
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }
}
