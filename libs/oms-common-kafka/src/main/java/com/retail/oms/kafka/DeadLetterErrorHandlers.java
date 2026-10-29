package com.retail.oms.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;

/**
 * The consumer error policy shared by all services (architecture 14.2):
 * <ul>
 *   <li>a failing record is retried with exponential backoff, then sent to the service's dead-letter topic;</li>
 *   <li>a Schema Registry outage gets its own, longer backoff: the event is fine, the registry just has to come back;</li>
 *   <li>{@link PoisonPillException} and the given business exceptions skip the retries: retrying cannot fix them.</li>
 * </ul>
 * The listener must receive the raw bytes ({@link AvroEventDecoder}), so the dead-letter topic holds exactly what
 * arrived and {@link DlqReplay} can put it back unchanged. Spring adds the {@code kafka_dlt-*} headers (original
 * topic, partition, offset, exception).
 * <p>
 * Metrics: {@code <prefix>.dead_lettered} and {@code <prefix>.registry_errors} (failed attempts caused by the registry).
 */
public final class DeadLetterErrorHandlers {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterErrorHandlers.class);

    private DeadLetterErrorHandlers() {
    }

    @SafeVarargs
    public static DefaultErrorHandler create(KafkaOperations<?, ?> deadLetterTemplate, String dlqTopic,
            RetryPolicy retry, RetryPolicy registryRetry, MeterRegistry meters, String metricPrefix,
            Class<? extends Exception>... notRetryable) {
        Counter deadLettered = meters.counter(metricPrefix + ".dead_lettered");
        Counter registryErrors = meters.counter(metricPrefix + ".registry_errors");
        var publisher = new DeadLetterPublishingRecoverer(deadLetterTemplate,
                (record, ex) -> new TopicPartition(dlqTopic, -1));      // -1: let the producer pick the partition
        var handler = new DefaultErrorHandler((record, ex) -> {
            deadLettered.increment();
            log.warn("Dead-lettering {}-{}@{} to {}: {}", record.topic(), record.partition(), record.offset(), dlqTopic,
                    rootMessage(ex));
            publisher.accept(record, ex);
        }, retry.toBackOff());
        var registryBackOff = registryRetry.toBackOff();
        handler.setBackOffFunction((record, ex) -> isRegistryError(ex) ? registryBackOff : null);   // null: the default
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                if (isRegistryError(ex)) {
                    registryErrors.increment();
                    log.warn("Schema Registry lookup failed for {}-{}@{} (attempt {}): {}", record.topic(),
                            record.partition(), record.offset(), deliveryAttempt, rootMessage(ex));
                } else {
                    // Spring itself says nothing until the retries are used up, so say what went wrong each time
                    log.warn("Handling {}-{}@{} failed (attempt {}): {}", record.topic(), record.partition(),
                            record.offset(), deliveryAttempt, rootMessage(ex));
                }
            }
        });
        handler.addNotRetryableExceptions(PoisonPillException.class);
        handler.addNotRetryableExceptions(notRetryable);
        return handler;
    }

    static boolean isRegistryError(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SchemaRegistryUnavailableException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
