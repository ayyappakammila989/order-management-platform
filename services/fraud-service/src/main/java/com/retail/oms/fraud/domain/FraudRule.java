package com.retail.oms.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Parameters of one rule (fraud_rule). What the code means is decided in {@code RuleEngine}. */
@Entity
@Table(name = "fraud_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FraudRule {

    @Id
    private String code;
    private String description;
    @Enumerated(EnumType.STRING)
    private RuleScope appliesTo;
    private boolean enabled;
    private int score;
    private BigDecimal threshold;
    @Column(name = "window_minutes")
    private Integer windowMinutes;

    /** For unit tests. */
    public static FraudRule of(String code, RuleScope scope, int score, String threshold, Integer windowMinutes) {
        FraudRule r = new FraudRule();
        r.code = code;
        r.description = code;
        r.appliesTo = scope;
        r.enabled = true;
        r.score = score;
        r.threshold = threshold == null ? null : new BigDecimal(threshold);
        r.windowMinutes = windowMinutes;
        return r;
    }

    public Duration window() {
        return windowMinutes == null ? null : Duration.ofMinutes(windowMinutes);
    }

    public FraudRule disabled() {
        FraudRule r = of(code, appliesTo, score, threshold == null ? null : threshold.toPlainString(), windowMinutes);
        r.enabled = false;
        return r;
    }
}
