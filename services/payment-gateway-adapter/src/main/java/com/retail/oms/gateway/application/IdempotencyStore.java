package com.retail.oms.gateway.application;

import com.retail.oms.events.payment.GatewayResult;
import com.retail.oms.gateway.config.GatewayProperties;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * pay:{idempotencyKey} (architecture 8: "gateway call result, 24 hours"). Before calling the gateway the key is
 * claimed with SET NX (one atomic step): only one attempt per key talks to the gateway. A final result (SUCCEEDED,
 * DECLINED, ERROR) replaces the claim for 24 h and is replayed to any repeat; a PENDING_RETRY frees the key so the
 * retry - with the same key - may call again. If Redis is down nothing is called: the command stays in Kafka.
 */
@Component
public class IdempotencyStore {

    static final String IN_PROGRESS = "IN_PROGRESS:";
    static final String RESULT = "RESULT:";

    public sealed interface Claim {
        record Claimed() implements Claim {
        }

        record InProgress(String requestId) implements Claim {
        }

        record Done(GatewayResult result) implements Claim {
        }
    }

    private final StringRedisTemplate redis;
    private final GatewayProperties props;

    public IdempotencyStore(StringRedisTemplate redis, GatewayProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public Claim claim(String idempotencyKey, String requestId) {
        String key = key(idempotencyKey);
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, IN_PROGRESS + requestId, props.inProgressTtl()))) {
            return new Claim.Claimed();
        }
        String value = Optional.ofNullable(redis.opsForValue().get(key)).orElse("");
        if (value.startsWith(RESULT)) {
            return new Claim.Done(decode(value.substring(RESULT.length())));
        }
        if (value.isEmpty()) {
            return claim(idempotencyKey, requestId);           // expired between SETNX and GET: try again
        }
        return new Claim.InProgress(value.substring(IN_PROGRESS.length()));
    }

    public void complete(String idempotencyKey, GatewayResult result) {
        if (Outcome.valueOf(result.getOutcome()).isFinal()) {
            redis.opsForValue().set(key(idempotencyKey), RESULT + encode(result), props.idempotencyTtl());
        } else {
            redis.delete(key(idempotencyKey));
        }
    }

    static String key(String idempotencyKey) {
        return "pay:" + idempotencyKey;
    }

    private static String encode(GatewayResult result) {
        try {
            ByteBuffer buffer = result.toByteBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static GatewayResult decode(String base64) {
        try {
            return GatewayResult.fromByteBuffer(ByteBuffer.wrap(Base64.getDecoder().decode(base64)));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unreadable pay: entry", e);
        }
    }
}
