package com.retail.oms.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.common.web.ApiError;
import com.retail.oms.common.web.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Writes the standard {@link ApiError} body for errors raised in the gateway itself. */
@Component
public class GatewayErrorWriter {

    private final ObjectMapper objectMapper;

    public GatewayErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ApiError build(ServerWebExchange exchange, int status, String errorCode, String message) {
        String traceId = exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER);
        return ApiError.of(status, errorCode, message, exchange.getRequest().getPath().value(), traceId);
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, String errorCode, String message) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(build(exchange, status.value(), errorCode, message));
        } catch (JsonProcessingException e) {
            body = "{\"status\":%d}".formatted(status.value()).getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
