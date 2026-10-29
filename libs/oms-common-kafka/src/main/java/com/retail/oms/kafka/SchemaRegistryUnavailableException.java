package com.retail.oms.kafka;

/**
 * The bytes look like a Confluent Avro message, but the writer schema could not be fetched from the Schema Registry
 * (registry down, timing out, still loading its store, or answering "schema not found"). The message itself may be
 * fine, so this is retried with backoff like any other outage.
 */
public class SchemaRegistryUnavailableException extends RuntimeException {

    public SchemaRegistryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
