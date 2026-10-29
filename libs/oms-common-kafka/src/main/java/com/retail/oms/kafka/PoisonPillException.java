package com.retail.oms.kafka;

/** The bytes can never be decoded (not Confluent Avro, or corrupt Avro): retrying cannot help, so dead-letter it now. */
public class PoisonPillException extends RuntimeException {

    public PoisonPillException(String message, Throwable cause) {
        super(message, cause);
    }
}
