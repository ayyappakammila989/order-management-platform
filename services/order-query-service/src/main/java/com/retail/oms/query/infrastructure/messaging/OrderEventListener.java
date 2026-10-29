package com.retail.oms.query.infrastructure.messaging;

import com.retail.oms.events.EventHeaders;
import com.retail.oms.kafka.AvroEventDecoder;
import com.retail.oms.query.application.OrderEventProcessor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Receives every oms.order.* event as raw bytes and decodes it here. An exception thrown here is handled by the
 * DefaultErrorHandler (retry with backoff, then dead-letter topic); the offset is committed once the record is
 * handled or dead-lettered. A retry re-reads the record from Kafka, so a registry lookup is really tried again.
 */
@Component
@Slf4j
public class OrderEventListener {

    private final OrderEventProcessor processor;
    private final AvroEventDecoder decoder;
    private final Counter deliveries;

    public OrderEventListener(OrderEventProcessor processor, AvroEventDecoder decoder, MeterRegistry meters) {
        this.processor = processor;
        this.decoder = decoder;
        this.deliveries = meters.counter("oms.query.events.deliveries");
    }

    // idIsGroup = false: the group is spring.kafka.consumer.group-id (order-query-service), not the listener id
    @KafkaListener(id = "order-query-events", idIsGroup = false, topicPattern = "${oms.query.topic-pattern}")
    public void onEvent(ConsumerRecord<String, byte[]> record) {
        deliveries.increment();      // counts every delivery attempt, including retries of the same record
        String correlationId = header(record, EventHeaders.CORRELATION_ID);
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            log.debug("Event from {}-{}@{}", record.topic(), record.partition(), record.offset());
            processor.process(decoder.decode(record.topic(), record.headers(), record.value()));
        } finally {
            MDC.remove("correlationId");
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
