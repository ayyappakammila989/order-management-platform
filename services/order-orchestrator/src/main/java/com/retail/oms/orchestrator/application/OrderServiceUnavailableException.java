package com.retail.oms.orchestrator.application;

/** A technical problem talking to order-service: the message is retried with backoff. */
public class OrderServiceUnavailableException extends RuntimeException {

    public OrderServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
