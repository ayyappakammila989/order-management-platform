package com.retail.oms.gateway.web;

import com.retail.oms.common.web.CorrelationId;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Adds X-Correlation-Id to the request if the client did not send one, so it reaches every downstream service,
 * and echoes it on the response. Runs first so even 401s and 404s carry it.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id = exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER);
        if (id == null || id.isBlank() || id.length() > 100) {
            id = UUID.randomUUID().toString();
        }
        String finalId = id;
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(CorrelationId.HEADER, finalId)))
                .build();
        mutated.getResponse().getHeaders().set(CorrelationId.HEADER, finalId);
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
