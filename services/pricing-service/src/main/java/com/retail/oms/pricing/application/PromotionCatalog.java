package com.retail.oms.pricing.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.retail.oms.pricing.config.PricingProperties;
import com.retail.oms.pricing.domain.Promotion;
import com.retail.oms.pricing.domain.PromotionRule;
import com.retail.oms.pricing.domain.PromotionScope;
import com.retail.oms.pricing.infrastructure.cache.PricingCache;
import com.retail.oms.pricing.infrastructure.persistence.PromotionRepository;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The promotions that are live now: Redis promo:active (5 min) in front of PostgreSQL. */
@Component
@RequiredArgsConstructor
public class PromotionCatalog {

    static final String KEY = "promo:active";

    private final PromotionRepository repository;
    private final PricingCache cache;
    private final PricingProperties props;
    private final Clock clock;

    public List<PromotionRule> live() {
        return cache.get(KEY, new TypeReference<List<PromotionRule>>() { }).orElseGet(() -> {
            List<PromotionRule> rules = repository.findLive(clock.instant()).stream().map(Promotion::toRule).toList();
            cache.put(KEY, rules, props.promoCacheTtl());
            return rules;
        });
    }

    /** Automatic promotions (no coupon code) on single SKUs. */
    public List<PromotionRule> automaticSkuPromotions() {
        return live().stream().filter(p -> p.couponCode() == null && p.scope() == PromotionScope.SKU).toList();
    }

    public Optional<PromotionRule> coupon(String code) {
        String wanted = code.trim().toUpperCase(Locale.ROOT);
        return live().stream().filter(p -> wanted.equals(p.couponCode())).findFirst();
    }

    /** Call when a promotion is published or changed (architecture 8: evicted on promo publish). */
    public void evict() {
        cache.evict(KEY);
    }
}
