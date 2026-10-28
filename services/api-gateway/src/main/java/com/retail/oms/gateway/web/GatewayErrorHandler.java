package com.retail.oms.gateway.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Last stop for exceptions in the gateway (no route, no instance of a service, upstream failures).
 * Replaces Spring Boot's default error JSON with {@link com.retail.oms.common.web.ApiError}.
 * Order -2 runs before Boot's DefaultErrorWebExceptionHandler (-1).
 */
@Slf4j
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private final GatewayErrorWriter errors;

    public GatewayErrorHandler(GatewayErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            HttpStatusCode status = rse.getStatusCode();
            log.warn("{} {} -> {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath().value(), rse.getMessage());
            return errors.write(exchange, status, "SYS-" + status.value(), reason(status));
        }
        log.error("Unhandled gateway error on {}", exchange.getRequest().getPath().value(), ex);
        return errors.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "SYS-500", "Unexpected error");
    }

    private static String reason(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        if (status.value() == 503) {
            return "Service temporarily unavailable";
        }
        return known != null ? known.getReasonPhrase() : "Request failed";
    }
}
