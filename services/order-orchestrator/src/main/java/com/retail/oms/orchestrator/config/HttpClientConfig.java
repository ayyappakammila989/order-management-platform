package com.retail.oms.orchestrator.config;

import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration.HttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Apache HttpClient 5 (needed for PATCH) re-sends a request by itself after a 503 or an I/O error. One retry policy is
 * enough: the Kafka consumer's backoff. Two layers would multiply the attempts and hide what really happened.
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    @Bean
    HttpClientBuilderCustomizer noAutomaticRetries() {
        return builder -> builder.disableAutomaticRetries();
    }
}
