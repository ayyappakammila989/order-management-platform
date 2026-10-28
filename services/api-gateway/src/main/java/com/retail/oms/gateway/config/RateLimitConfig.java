package com.retail.oms.gateway.config;

import java.util.Optional;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    /** One bucket per authenticated user (JWT subject); falls back to the client address when anonymous. */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName())
                .switchIfEmpty(Mono.fromSupplier(() -> Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                        .map(a -> a.getAddress().getHostAddress())
                        .orElse("unknown")));
    }
}
