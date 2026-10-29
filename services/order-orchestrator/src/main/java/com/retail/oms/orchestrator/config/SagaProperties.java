package com.retail.oms.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("oms.saga")
public record SagaProperties(Duration stepTimeout, int compensationMaxAttempts, Duration timeoutCheckInterval) {
}
