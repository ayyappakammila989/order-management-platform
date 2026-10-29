package com.retail.oms.kafka;

import java.time.Duration;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;

/** Exponential backoff, e.g. 1 s, 2 s, 4 s, 8 s for (1 s, 2.0, 10 s, 4). Bindable from application.yml. */
public record RetryPolicy(Duration initialInterval, double multiplier, Duration maxInterval, int maxRetries) {

    public BackOff toBackOff() {
        var backoff = new ExponentialBackOffWithMaxRetries(maxRetries);
        backoff.setInitialInterval(initialInterval.toMillis());
        backoff.setMultiplier(multiplier);
        backoff.setMaxInterval(maxInterval.toMillis());
        return backoff;
    }
}
