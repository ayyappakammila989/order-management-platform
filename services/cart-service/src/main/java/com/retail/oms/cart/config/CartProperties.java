package com.retail.oms.cart.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("oms.cart")
public record CartProperties(Duration ttl, Duration checkoutRecordTtl, int maxLines, int maxQuantityPerLine,
                             String defaultCurrency) {
}
