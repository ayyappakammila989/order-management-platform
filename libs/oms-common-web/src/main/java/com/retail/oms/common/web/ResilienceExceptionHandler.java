package com.retail.oms.common.web;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Only registered when Resilience4j is on the classpath. An open circuit breaker is a 503, not a 500. */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ResilienceExceptionHandler {

    @ExceptionHandler(CallNotPermittedException.class)
    ResponseEntity<ApiError> handleOpenCircuit(CallNotPermittedException ex, HttpServletRequest request) {
        log.warn("Circuit open: {}", ex.getMessage());
        return GlobalExceptionHandler.respond(
                ApiErrorFactory.build(request.getRequestURI(), 503, "SYS-503", "Service temporarily unavailable"));
    }
}
