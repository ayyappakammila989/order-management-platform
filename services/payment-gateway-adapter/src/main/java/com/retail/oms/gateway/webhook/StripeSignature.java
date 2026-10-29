package com.retail.oms.gateway.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe-Signature check (architecture 7.2), the same as Stripe's libraries do it:
 * header {@code t=<unix seconds>,v1=<hex>[,v1=...]}; expected v1 = HMAC-SHA256(secret, t + "." + raw body).
 * The timestamp must be within the tolerance (default 5 minutes), so a captured request cannot be replayed later.
 * Comparison in constant time. The RAW body is signed: it must be checked before any JSON parsing.
 */
public class StripeSignature {

    private final String secret;
    private final Duration tolerance;
    private final Clock clock;

    public StripeSignature(String secret, Duration tolerance, Clock clock) {
        this.secret = secret;
        this.tolerance = tolerance;
        this.clock = clock;
    }

    public void verify(String header, byte[] body) {
        if (header == null || header.isBlank()) {
            throw new WebhookRejectedException("Stripe-Signature header missing");
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    throw new WebhookRejectedException("Stripe-Signature timestamp is not a number");
                }
            } else if ("v1".equals(kv[0])) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new WebhookRejectedException("Stripe-Signature needs t= and v1=");
        }
        long age = Instant.now(clock).getEpochSecond() - timestamp;
        if (Math.abs(age) > tolerance.toSeconds()) {
            throw new WebhookRejectedException("Stripe-Signature timestamp outside the tolerance (" + age + " s old)");
        }
        byte[] expected = HexFormat.of().formatHex(sign(timestamp, body)).getBytes(StandardCharsets.US_ASCII);
        boolean match = signatures.stream()
                .anyMatch(s -> MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.US_ASCII)));
        if (!match) {
            throw new WebhookRejectedException("Stripe-Signature does not match");
        }
    }

    /** Also used by tests and scripts/send-stripe-webhook.sh's Java twin to sign a body. */
    public String header(long timestamp, byte[] body) {
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(sign(timestamp, body));
    }

    private byte[] sign(long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
