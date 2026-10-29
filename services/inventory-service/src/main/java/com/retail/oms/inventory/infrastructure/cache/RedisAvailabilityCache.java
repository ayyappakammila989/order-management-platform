package com.retail.oms.inventory.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.inventory.application.Availability;
import com.retail.oms.inventory.application.AvailabilityCache;
import com.retail.oms.inventory.config.InventoryProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** atp:{sku}:{zip} as JSON, 30 s. Any Redis error is logged and treated as a miss: the database answers instead. */
@Component
@Slf4j
public class RedisAvailabilityCache implements AvailabilityCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final InventoryProperties props;

    public RedisAvailabilityCache(StringRedisTemplate redis, ObjectMapper json, InventoryProperties props) {
        this.redis = redis;
        this.json = json;
        this.props = props;
    }

    static String key(String sku, String zip) {
        return "atp:" + sku + ":" + (zip == null || zip.isBlank() ? "any" : zip);
    }

    @Override
    public Optional<Availability> get(String sku, String zip) {
        try {
            String value = redis.opsForValue().get(key(sku, zip));
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, Availability.class));
        } catch (Exception e) {
            log.warn("Availability cache read failed, using the database: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(Availability a) {
        try {
            redis.opsForValue().set(key(a.sku(), a.zip()), json.writeValueAsString(a), props.availabilityCacheTtl());
        } catch (Exception e) {
            log.warn("Availability cache write failed: {}", e.toString());
        }
    }

    /** SCAN, not KEYS: KEYS blocks Redis while it walks the whole key space. */
    @Override
    public void evict(String sku) {
        try {
            List<String> keys = new ArrayList<>();
            try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match("atp:" + sku + ":*").count(500).build())) {
                cursor.forEachRemaining(keys::add);
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            // the entry then lives until its 30 s TTL ends: a slightly stale pre-check, which the saga re-checks anyway
            log.warn("Availability cache eviction for {} failed: {}", sku, e.toString());
        }
    }
}
