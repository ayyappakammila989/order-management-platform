package com.retail.oms.pricing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "product_price")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductPrice {

    @Id
    private String sku;
    private String currency;
    @Column(name = "base_price")
    private BigDecimal basePrice;
    @Column(name = "tax_code")
    private String taxCode;

    public PriceInfo toInfo() {
        return new PriceInfo(sku, currency, basePrice, taxCode);
    }
}
