package com.retail.oms.query.infrastructure.messaging;

import com.retail.oms.events.EventHeaders;
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
 * Receives every oms.order.* event. An exception thrown here is handled by the DefaultErrorHandler (retry with
 * backoff, then dead-letter topic); the offset is committed once the record is handled or dead-lettered.
 */
@Component
@Slf4j
public class OrderEventListener {

    private final OrderEventProcessor processor;
    private final Counter deliveries;

    public OrderEventListener(OrderEventProcessor processor, MeterRegistry meters) {
        this.processor = processor;
        this.deliveries = meters.counter("oms.query.events.deliveries");
    }

    @KafkaListener(id = "order-query-events", topicPattern = "${oms.query.topic-pattern}")
    public void onEvent(ConsumerRecord<String, Object> record) {
        deliveries.increment();      // counts every delivery attempt, including retries of the same record
        String correlationId = header(record, EventHeaders.CORRELATION_ID);
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            log.debug("Event from {}-{}@{}", record.topic(), record.partition(), record.offset());
            processor.process(record.value());
        } finally {
            MDC.remove("correlationId");
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
