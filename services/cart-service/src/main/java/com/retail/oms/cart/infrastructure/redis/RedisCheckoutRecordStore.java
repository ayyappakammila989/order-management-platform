package com.retail.oms.cart.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.cart.application.CheckoutRecordStore;
import com.retail.oms.cart.config.CartProperties;
import com.retail.oms.cart.infrastructure.client.OrderClient;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisCheckoutRecordStore implements CheckoutRecordStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CartProperties props;

    static String key(String customerId, String idempotencyKey) {
        return "checkout:" + customerId + ":" + idempotencyKey;
    }

    @Override
    public Optional<CheckoutRecord> find(String customerId, String idempotencyKey) {
        String json = redis.opsForValue().get(key(customerId, idempotencyKey));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, CheckoutRecord.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read checkout record", e);
        }
    }

    @Override
    public boolean saveRequest(String customerId, String idempotencyKey, String cartId,
            OrderClient.OrderRequest request) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(customerId, idempotencyKey),
                write(new CheckoutRecord(cartId, request, null)), props.checkoutRecordTtl()));
    }

    @Override
    public void saveOrder(String customerId, String idempotencyKey, CheckoutRecord record) {
        redis.opsForValue().set(key(customerId, idempotencyKey), write(record), props.checkoutRecordTtl());
    }

    private String write(CheckoutRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise checkout record", e);
        }
    }
}
