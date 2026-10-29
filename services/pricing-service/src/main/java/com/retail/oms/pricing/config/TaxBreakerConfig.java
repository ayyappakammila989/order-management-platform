package com.retail.oms.pricing.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Circuit breaker around tax-service (architecture 6.1 style: 50% failures, then open for 30 s). */
@Configuration(proxyBeanMethods = false)
public class TaxBreakerConfig {

    public static final String TAX_BREAKER = "tax-service";

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> taxBreakerCustomizer(PricingProperties props) {
        PricingProperties.TaxBreaker b = props.taxBreaker();
        return factory -> factory.configure(builder -> builder
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(b.slidingWindowSize())
                        .minimumNumberOfCalls(b.minimumCalls())
                        .failureRateThreshold(b.failureRateThreshold())
                        .waitDurationInOpenState(b.openWait())
                        .build())
                // Feign has its own connect/read timeouts; this one is only the outer safety net
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build())
                .build(), TAX_BREAKER);
    }
}
