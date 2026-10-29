package com.retail.oms.gateway.application;

/** GatewayResult.outcome. Only SUCCEEDED, DECLINED and ERROR are final and kept in pay:{key}. */
public enum Outcome {
    SUCCEEDED, DECLINED, ERROR, PENDING_RETRY;

    public boolean isFinal() {
        return this != PENDING_RETRY;
    }
}
