package com.retail.oms.cart.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** oms.cart.inventory-check: the availability pre-check at checkout (architecture 3.1 step 4 and the resilience table). */
@ConfigurationProperties("oms.cart.inventory-check")
public record InventoryCheckProperties(boolean enabled, int retries, Duration retryWait) {
}
