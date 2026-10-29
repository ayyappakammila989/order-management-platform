package com.retail.oms.gateway.application;

/** The gateway did not give a usable answer (5xx, 429, timeout, connection error): counts for the circuit breaker. */
public class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message) {
        super(message);
    }

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
