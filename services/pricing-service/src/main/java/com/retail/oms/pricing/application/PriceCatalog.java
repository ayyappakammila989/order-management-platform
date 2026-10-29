package com.retail.oms.pricing.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.retail.oms.common.web.OmsException;
import com.retail.oms.pricing.config.PricingProperties;
import com.retail.oms.pricing.domain.PriceInfo;
import com.retail.oms.pricing.domain.ProductPrice;
import com.retail.oms.pricing.infrastructure.cache.PricingCache;
import com.retail.oms.pricing.infrastructure.persistence.ProductPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Base price of a SKU: Redis price:{sku} (10 min) in front of PostgreSQL. */
@Component
@RequiredArgsConstructor
public class PriceCatalog {

    private final ProductPriceRepository repository;
    private final PricingCache cache;
    private final PricingProperties props;

    public PriceInfo priceOf(String sku) {
        String key = "price:" + sku;
        return cache.get(key, new TypeReference<PriceInfo>() { }).orElseGet(() -> {
            PriceInfo info = repository.findById(sku).map(ProductPrice::toInfo).orElseThrow(() ->
                    new OmsException("PRC-404", HttpStatus.NOT_FOUND, "Unknown SKU: " + sku));
            cache.put(key, info, props.priceCacheTtl());
            return info;
        });
    }
}
