package com.retail.oms.query.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("oms.query")
public record QueryProperties(String index, String alias, String schemaRegistryUrl, String topicPattern,
                              String dlqTopic, Duration processedEventTtl, Retry retry) {

    public record Retry(Duration initialInterval, double multiplier, Duration maxInterval, int maxRetries) {
    }
}
