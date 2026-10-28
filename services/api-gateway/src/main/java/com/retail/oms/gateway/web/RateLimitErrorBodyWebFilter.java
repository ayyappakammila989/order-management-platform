package com.retail.oms.gateway.web;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Spring Cloud Gateway's RequestRateLimiter answers 429 with an empty body (it only calls setComplete()).
 * This filter gives that 429 the standard {@link com.retail.oms.common.web.ApiError} body like every other error.
 */
@Component
public class RateLimitErrorBodyWebFilter implements WebFilter, Ordered {

    private final GatewayErrorWriter errors;

    public RateLimitErrorBodyWebFilter(GatewayErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                if (!isCommitted() && HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode())) {
                    // write on the undecorated exchange so this does not call setComplete() again
                    return errors.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "SYS-429",
                            "Too many requests. Please slow down and try again shortly.");
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(decorated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }
}
