package com.retail.oms.orchestrator.application.engine;

import com.retail.oms.orchestrator.domain.SagaData;
import java.util.UUID;

/** What a step may read: the saga data, why the saga is compensating (if it is), the correlation id. */
public record StepContext(UUID sagaId, UUID orderId, SagaData data, String failureReason, String correlationId) {
}
