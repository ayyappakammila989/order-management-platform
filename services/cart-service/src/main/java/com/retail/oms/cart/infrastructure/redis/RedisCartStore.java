package com.retail.oms.cart.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.cart.application.CartStore;
import com.retail.oms.cart.config.CartProperties;
import com.retail.oms.cart.domain.Cart;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * cart:{cartId} is a hash with the cart as JSON ("json") and a counter ("v"). The compare-and-set runs as one Lua
 * script, so two requests changing the same cart cannot overwrite each other: the loser gets false and retries.
 */
@Component
@RequiredArgsConstructor
public class RedisCartStore implements CartStore {

    private static final DefaultRedisScript<Long> REPLACE_IF_VERSION = new DefaultRedisScript<>("""
            local v = redis.call('HGET', KEYS[1], 'v')
            if v == false or tonumber(v) ~= tonumber(ARGV[1]) then return 0 end
            redis.call('HSET', KEYS[1], 'v', tostring(tonumber(ARGV[1]) + 1), 'json', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CartProperties props;

    static String key(String cartId) {
        return "cart:" + cartId;
    }

    @Override
    public void create(Cart cart) {
        String key = key(cart.cartId());
        redis.opsForHash().put(key, "v", "1");
        redis.opsForHash().put(key, "json", write(cart));
        redis.expire(key, props.ttl());
    }

    @Override
    public Optional<VersionedCart> find(String cartId) {
        String key = key(cartId);
        List<Object> fields = redis.opsForHash().multiGet(key, List.of("v", "json"));
        if (fields.get(0) == null || fields.get(1) == null) {
            return Optional.empty();
        }
        redis.expire(key, props.ttl());
        return Optional.of(new VersionedCart(read((String) fields.get(1)), Long.parseLong((String) fields.get(0))));
    }

    @Override
    public boolean replace(Cart cart, long expectedVersion) {
        Long result = redis.execute(REPLACE_IF_VERSION, List.of(key(cart.cartId())), String.valueOf(expectedVersion),
                write(cart), String.valueOf(props.ttl().toMillis()));
        return Long.valueOf(1).equals(result);
    }

    @Override
    public void delete(String cartId) {
        redis.delete(key(cartId));
    }

    private String write(Cart cart) {
        try {
            return objectMapper.writeValueAsString(cart);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise cart", e);
        }
    }

    private Cart read(String json) {
        try {
            return objectMapper.readValue(json, Cart.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read cart from Redis", e);
        }
    }
}
