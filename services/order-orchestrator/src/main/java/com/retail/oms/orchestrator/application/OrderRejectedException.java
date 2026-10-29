package com.retail.oms.orchestrator.application;

/** order-service gave a business "no" (4xx with an error code). */
public class OrderRejectedException extends RuntimeException {

    private final String errorCode;

    public OrderRejectedException(String errorCode, String message) {
        super(errorCode + ": " + message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
