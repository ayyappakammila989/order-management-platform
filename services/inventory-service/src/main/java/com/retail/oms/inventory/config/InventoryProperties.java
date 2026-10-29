package com.retail.oms.inventory.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("oms.inventory")
public record InventoryProperties(Duration reservationTtl, Duration expiryInterval, int expiryBatchSize,
                                  Duration lockTimeout, Duration availabilityCacheTtl, String dlqTopic,
                                  int topicPartitions) {
}
