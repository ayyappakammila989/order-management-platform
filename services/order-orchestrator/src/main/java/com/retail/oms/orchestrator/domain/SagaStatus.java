package com.retail.oms.orchestrator.domain;

/**
 * RUNNING: steps go forward. COMPENSATING: a step failed, completed steps are undone in reverse order.
 * COMPLETED / COMPENSATED: finished. FAILED: a compensation could not be done; needs a person (support/ops).
 */
public enum SagaStatus {
    RUNNING,
    COMPENSATING,
    COMPLETED,
    COMPENSATED,
    FAILED;

    public boolean isFinished() {
        return this == COMPLETED || this == COMPENSATED || this == FAILED;
    }
}
