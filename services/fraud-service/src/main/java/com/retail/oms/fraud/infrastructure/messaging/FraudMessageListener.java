package com.retail.oms.fraud.infrastructure.messaging;

import com.retail.oms.events.EventHeaders;
import com.retail.oms.events.Topics;
import com.retail.oms.events.fraud.FraudCancelCommand;
import com.retail.oms.events.fraud.FraudCheckCommand;
import com.retail.oms.fraud.application.FraudService;
import com.retail.oms.fraud.application.InvalidMessageException;
import com.retail.oms.fraud.application.SagaRef;
import com.retail.oms.fraud.domain.CheckKind;
import com.retail.oms.fraud.domain.Subject;
import com.retail.oms.kafka.AvroEventDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Saga commands from order-orchestrator. Retry with backoff, then oms.fraud.dlq (libs/oms-common-kafka). */
@Component
public class FraudMessageListener {

    private final AvroEventDecoder decoder;
    private final FraudService fraud;

    public FraudMessageListener(AvroEventDecoder decoder, FraudService fraud) {
        this.decoder = decoder;
        this.fraud = fraud;
    }

    @KafkaListener(id = "fraud-messages", idIsGroup = false, topics = {Topics.FRAUD_CHECK_CMD, Topics.FRAUD_CANCEL_CMD})
    public void onMessage(ConsumerRecord<String, byte[]> record) {
        String correlationId = header(record, EventHeaders.CORRELATION_ID);
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            Object message = decoder.decode(record.topic(), record.headers(), record.value());
            switch (message) {
                case FraudCheckCommand cmd -> fraud.screenForSaga(subject(cmd), uuid(cmd.getOrderId()),
                        new SagaRef(cmd.getCommandId(), uuid(cmd.getSagaId()), correlationId));
                case FraudCancelCommand cmd -> fraud.cancelForSaga(uuid(cmd.getOrderId()), cmd.getReason(),
                        new SagaRef(cmd.getCommandId(), uuid(cmd.getSagaId()), correlationId));
                default -> throw new InvalidMessageException("Unexpected " + message.getClass().getSimpleName()
                        + " on " + record.topic());
            }
        } finally {
            MDC.remove("correlationId");
        }
    }

    static Subject subject(FraudCheckCommand cmd) {
        if (cmd.getGrandTotal() == null || cmd.getCustomerId() == null || cmd.getCustomerId().isBlank()) {
            throw new InvalidMessageException("Fraud check " + cmd.getCommandId() + " without customer or total");
        }
        return new Subject(CheckKind.ORDER, cmd.getCustomerId(), cmd.getGrandTotal(), cmd.getCurrency(),
                cmd.getShipToZip(), cmd.getPaymentMethod() == null ? null : cmd.getPaymentMethod().getMethodToken(),
                cmd.getLines().stream().map(l -> new Subject.Line(l.getSku(), l.getQuantity())).toList());
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidMessageException("Not a UUID: " + value);
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
