package com.retail.oms.fraud.application;

import java.util.UUID;

/** Where to send the answer of a saga command. */
public record SagaRef(String commandId, UUID sagaId, String correlationId) {
}
