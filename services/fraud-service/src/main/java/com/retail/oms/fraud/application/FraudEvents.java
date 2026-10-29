package com.retail.oms.fraud.application;

import com.retail.oms.events.Topics;
import com.retail.oms.events.fraud.FraudCheckCancelledEvent;
import com.retail.oms.events.fraud.FraudDecisionEvent;
import com.retail.oms.fraud.domain.Decision;
import com.retail.oms.fraud.domain.FraudCheck;
import com.retail.oms.kafka.outbox.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Saga replies, written to the outbox in the caller's transaction. Key = orderId. */
@Component
public class FraudEvents {

    private final Outbox outbox;
    private final Clock clock;

    public FraudEvents(Outbox outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    public void decision(FraudCheck check, Decision decision, String caseId, String decidedBy, String correlationId) {
        String id = UUID.randomUUID().toString();
        var event = FraudDecisionEvent.newBuilder().setEventId(id).setOccurredAt(Instant.now(clock))
                .setCommandId(check.getCommandId()).setSagaId(check.getSagaId().toString())
                .setOrderId(check.getOrderId().toString()).setCheckId(check.getCheckId().toString())
                .setDecision(decision.name()).setScore(check.getScore()).setReasons(check.reasonList())
                .setCaseId(caseId).setDecidedBy(decidedBy).build();
        outbox.add(Topics.fraudDecision(decision.name()), check.getOrderId().toString(), id, event, correlationId);
    }

    public void cancelled(SagaRef saga, UUID orderId, String note) {
        String id = UUID.randomUUID().toString();
        var event = FraudCheckCancelledEvent.newBuilder().setEventId(id).setOccurredAt(Instant.now(clock))
                .setCommandId(saga.commandId()).setSagaId(saga.sagaId().toString()).setOrderId(orderId.toString())
                .setNote(note).build();
        outbox.add(Topics.FRAUD_CANCELLED, orderId.toString(), id, event, saga.correlationId());
    }
}
