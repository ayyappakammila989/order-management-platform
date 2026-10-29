package com.retail.oms.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * oms.gateway.*. Secrets come from the environment (STRIPE_API_KEY, ...; architecture 6.3); the defaults only work
 * with the local mocks.
 *
 * @param callTimeout   time limiter around one gateway call (architecture 6.4: 10 s)
 * @param inProgressTtl how long pay:{key} says "in progress": longer than one call, so a crashed attempt frees the key
 */
@ConfigurationProperties("oms.gateway")
public record GatewayProperties(Stripe stripe, PayPal paypal, Duration callTimeout, Duration connectTimeout,
                                Duration idempotencyTtl, Duration inProgressTtl, Breaker breaker, Webhooks webhooks,
                                int topicPartitions, String dlqTopic, int consumers) {

    /** @param mockMode stripe-mock answers fixture statuses: treat any 2xx as success (false for real Stripe test mode) */
    public record Stripe(String baseUrl, String apiKey, String webhookSecret, Duration signatureTolerance, boolean mockMode) {
    }

    public record PayPal(String baseUrl, String clientId, String clientSecret, String webhookId) {
    }

    public record Breaker(int failureRateThreshold, int slidingWindowSize, int minimumCalls, Duration openWait) {
    }

    /** @param allowedCidrs who may call /webhooks/** (architecture 6.2: IP allow-list on top of the signature) */
    public record Webhooks(Duration dedupeTtl, List<String> allowedCidrs) {
    }
}
