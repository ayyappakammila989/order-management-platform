package com.retail.oms.pricing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "promotion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Column(name = "coupon_code")
    private String couponCode;
    @Enumerated(EnumType.STRING)
    private PromotionScope scope;
    private String sku;
    @Enumerated(EnumType.STRING)
    private PromotionKind kind;
    private BigDecimal value;
    @Column(name = "min_subtotal")
    private BigDecimal minSubtotal;
    @Column(name = "starts_at")
    private Instant startsAt;
    @Column(name = "ends_at")
    private Instant endsAt;
    private boolean active;

    public PromotionRule toRule() {
        return new PromotionRule(id, name, couponCode, scope, sku, kind, value, minSubtotal);
    }
}
