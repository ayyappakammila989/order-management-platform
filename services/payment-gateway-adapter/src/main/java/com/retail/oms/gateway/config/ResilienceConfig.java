package com.retail.oms.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One circuit breaker per gateway (architecture 6.4), used by the Camel circuitBreaker EIP by bean name. Created here
 * rather than by Camel so they are in one registry and exported as resilience4j.circuitbreaker.state etc.
 * Only {@code GatewayUnavailableException} (5xx, 429, timeouts, connection errors) counts as a failure: a declined
 * card is a normal answer, not a sick gateway.
 */
@Configuration(proxyBeanMethods = false)
public class ResilienceConfig {

    public static final String STRIPE = "stripeCircuitBreaker";
    public static final String PAYPAL = "paypalCircuitBreaker";

    @Bean
    CircuitBreakerRegistry gatewayBreakers(GatewayProperties props, MeterRegistry meters) {
        var b = props.breaker();
        var config = CircuitBreakerConfig.custom()
                .failureRateThreshold(b.failureRateThreshold())
                .slidingWindowSize(b.slidingWindowSize())
                .minimumNumberOfCalls(b.minimumCalls())
                .waitDurationInOpenState(b.openWait())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(com.retail.oms.gateway.application.GatewayUnavailableException.class,
                        java.util.concurrent.TimeoutException.class, java.io.IOException.class)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    @Bean(STRIPE)
    CircuitBreaker stripeCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("stripe");
    }

    @Bean(PAYPAL)
    CircuitBreaker paypalCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("paypal");
    }
}
