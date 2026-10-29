package com.retail.oms.orchestrator.config;

import com.retail.oms.kafka.RetryPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** oms.kafka.*: Schema Registry and consumer retry settings (Spring's own spring.kafka.* holds the rest). */
@ConfigurationProperties("oms.kafka")
public record MessagingProperties(String schemaRegistryUrl, Duration schemaRegistryTimeout, int listenerConcurrency,
                                  String dlqTopic, int topicPartitions, RetryPolicy retry, RetryPolicy registryRetry) {
}
