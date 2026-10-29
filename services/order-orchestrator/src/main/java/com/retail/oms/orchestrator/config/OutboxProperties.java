package com.retail.oms.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("oms.outbox")
public record OutboxProperties(Duration pollInterval, int batchSize, Duration sendTimeout, Duration retention) {
}
