package com.retail.oms.gateway.web;

import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Two jobs, both before security so they apply to everyone:
 * 1. block any path with an {@code internal} segment ({@code /internal/**} is for service-to-service calls only),
 *    comparing decoded segments so {@code /%69nternal} and {@code /a/../internal} do not slip through;
 * 2. strip headers a client must never be able to set ({@code X-Internal-*}, {@code X-User-*}).
 */
@Slf4j
@Component
public class InternalRequestWebFilter implements WebFilter, Ordered {

    private static final String[] STRIPPED_PREFIXES = {"x-internal-", "x-user-"};

    private final GatewayErrorWriter errors;

    public InternalRequestWebFilter(GatewayErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        boolean blocked = exchange.getRequest().getPath().pathWithinApplication().elements().stream()
                .filter(PathContainer.PathSegment.class::isInstance)
                .map(e -> ((PathContainer.PathSegment) e).valueToMatch())
                .anyMatch(segment -> segment.equalsIgnoreCase("internal"));
        if (blocked) {
            log.warn("Blocked /internal path: {}", exchange.getRequest().getPath().value());
            return errors.write(exchange, HttpStatus.FORBIDDEN, "SEC-403", "Access denied");
        }
        ServerWebExchange cleaned = exchange.mutate()
                .request(r -> r.headers(h -> h.keySet().removeIf(InternalRequestWebFilter::isStripped)))
                .build();
        return chain.filter(cleaned);
    }

    private static boolean isStripped(String header) {
        String lower = header.toLowerCase(Locale.ROOT);
        for (String prefix : STRIPPED_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
