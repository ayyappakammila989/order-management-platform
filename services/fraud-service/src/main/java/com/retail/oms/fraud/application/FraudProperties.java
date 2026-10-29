package com.retail.oms.fraud.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** oms.fraud.*: the score at which an order goes to review / is rejected. */
@ConfigurationProperties("oms.fraud")
public record FraudProperties(int reviewScore, int rejectScore, String dlqTopic, int topicPartitions) {
}
