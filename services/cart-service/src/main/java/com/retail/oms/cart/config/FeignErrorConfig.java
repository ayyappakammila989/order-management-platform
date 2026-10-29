package com.retail.oms.cart.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.common.web.OmsException;
import feign.Response;
import feign.codec.ErrorDecoder;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

/**
 * What the customer sees when a dependency says no. A rejection that is about the request (unknown SKU, bad coupon,
 * totals mismatch, key reused) passes through with the downstream error code. Everything else (5xx, 401/403 because
 * our own service token was refused) is our problem, not the customer's: it becomes a plain 503.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class FeignErrorConfig {

    private static final Set<Integer> PASS_THROUGH = Set.of(400, 404, 409, 422);

    @Bean
    ErrorDecoder downstreamErrorDecoder(ObjectMapper objectMapper) {
        return (methodKey, response) -> decode(objectMapper, methodKey, response);
    }

    static OmsException decode(ObjectMapper objectMapper, String methodKey, Response response) {
        if (PASS_THROUGH.contains(response.status())) {
            try (var body = response.body() == null ? null : response.body().asInputStream()) {
                if (body != null) {
                    JsonNode json = objectMapper.readTree(body);
                    if (json.hasNonNull("errorCode")) {
                        return new OmsException(json.get("errorCode").asText(), HttpStatus.valueOf(response.status()),
                                json.path("message").asText("Request rejected"));
                    }
                }
            } catch (IOException e) {
                log.warn("Unreadable error body from {}: {}", methodKey, e.toString());
            }
        }
        log.error("{} answered HTTP {}", methodKey, response.status());
        return new OmsException("CRT-503", HttpStatus.SERVICE_UNAVAILABLE,
                "A service needed for this request is not available, please try again shortly");
    }

    /** Business rejections (OmsException) must not count as failures, or a few bad coupons would open the breaker. */
    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> feignBreakerDefaults() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .ignoreExceptions(OmsException.class)
                        .slidingWindowSize(20).minimumNumberOfCalls(10).failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30)).build())
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(8)).build())
                .build());
    }
}
