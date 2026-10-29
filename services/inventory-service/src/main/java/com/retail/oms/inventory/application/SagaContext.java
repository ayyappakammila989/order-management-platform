package com.retail.oms.inventory.application;

/**
 * Where a reserve/release request came from. For a saga command, replies go to the orchestrator; for a REST call
 * (no command) there is no reply, only the inventory.changed facts.
 */
public record SagaContext(String commandId, String sagaId, String correlationId) {

    public static SagaContext none(String correlationId) {
        return new SagaContext(null, null, correlationId);
    }

    public boolean isCommand() {
        return commandId != null;
    }
}
