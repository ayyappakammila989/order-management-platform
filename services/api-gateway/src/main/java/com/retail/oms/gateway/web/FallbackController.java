package com.retail.oms.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import com.retail.oms.common.web.ApiError;

/** Target of the circuit breaker filters: a friendly 503 in the standard error format (architecture 6.4). */
@RestController
public class FallbackController {

    private final GatewayErrorWriter errors;

    public FallbackController(GatewayErrorWriter errors) {
        this.errors = errors;
    }

    @RequestMapping("/fallback/{route}")
    ResponseEntity<ApiError> fallback(@PathVariable String route, ServerWebExchange exchange) {
        ApiError body = errors.build(exchange, 503, "SYS-503",
                "The " + route + " service is temporarily unavailable. Please try again shortly.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
