package com.retail.oms.query.config;

import com.retail.oms.kafka.RetryPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("oms.query")
public record QueryProperties(String index, String alias, String schemaRegistryUrl, Duration schemaRegistryTimeout,
                              String topicPattern, String dlqTopic, Duration processedEventTtl, RetryPolicy retry,
                              RetryPolicy registryRetry) {
}
