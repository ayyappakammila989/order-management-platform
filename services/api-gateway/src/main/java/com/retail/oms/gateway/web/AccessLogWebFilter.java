package com.retail.oms.gateway.web;

import com.retail.oms.common.web.CorrelationId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** One line per request: method, path, status, time taken. (Reactive code has no thread-bound MDC, so the id is in the text.) */
@Slf4j
@Component
public class AccessLogWebFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long start = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("{} {} -> {} in {} ms correlationId={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(),
                    exchange.getResponse().getStatusCode() != null ? exchange.getResponse().getStatusCode().value() : "-",
                    ms,
                    exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
