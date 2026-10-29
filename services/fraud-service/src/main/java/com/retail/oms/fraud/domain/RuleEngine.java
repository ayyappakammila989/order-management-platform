package com.retail.oms.fraud.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Scores a subject against the enabled rules. Pure logic: everything it needs from the past comes through
 * {@link History}, so every rule can be unit tested with edge values (architecture 12.1).
 * A threshold is inclusive: an order of exactly 1000.00 hits HIGH_ORDER_VALUE; the 4th token hits CARD_TESTING.
 */
@Slf4j
public class RuleEngine {

    /** What the rules need to know besides the subject itself. Counts do NOT include the subject being screened. */
    public interface History {

        boolean blocked(String type, String value);

        int checksSince(CheckKind kind, String customerId, java.time.Duration window);

        /** Distinct payment tokens the customer used in earlier checks inside the window. */
        java.util.Set<String> tokensSince(String customerId, java.time.Duration window);
    }

    private final int reviewScore;
    private final int rejectScore;

    public RuleEngine(int reviewScore, int rejectScore) {
        if (reviewScore > rejectScore) {
            throw new IllegalArgumentException("review-score must not be above reject-score");
        }
        this.reviewScore = reviewScore;
        this.rejectScore = rejectScore;
    }

    public Evaluation evaluate(List<FraudRule> rules, Subject s, History history) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        for (FraudRule rule : rules.stream().sorted(Comparator.comparing(FraudRule::getCode)).toList()) {
            if (!rule.isEnabled() || !rule.getAppliesTo().covers(s.kind())) {
                continue;
            }
            if (hits(rule, s, history)) {
                score += rule.getScore();
                reasons.add(rule.getCode());
            }
        }
        Decision decision = score >= rejectScore ? Decision.REJECTED : score >= reviewScore ? Decision.REVIEW : Decision.APPROVED;
        return new Evaluation(score, decision, List.copyOf(reasons));
    }

    private boolean hits(FraudRule rule, Subject s, History history) {
        BigDecimal t = rule.getThreshold();
        return switch (rule.getCode()) {
            case "BLOCKED_CUSTOMER" -> history.blocked("CUSTOMER", s.customerId());
            case "BLOCKED_ZIP" -> s.shipToZip() != null && history.blocked("ZIP", s.shipToZip());
            case "HIGH_ORDER_VALUE", "VERY_HIGH_ORDER_VALUE", "HIGH_REFUND_VALUE" -> s.amount().compareTo(t) >= 0;
            case "LARGE_QUANTITY" -> s.lines().stream().anyMatch(l -> l.quantity() >= t.intValue());
            case "ORDER_VELOCITY", "REFUND_VELOCITY" ->
                    history.checksSince(s.kind(), s.customerId(), rule.window()) + 1 >= t.intValue();
            case "CARD_TESTING" -> {
                if (s.paymentToken() == null) {
                    yield false;
                }
                var tokens = new java.util.HashSet<>(history.tokensSince(s.customerId(), rule.window()));
                tokens.add(s.paymentToken());
                yield tokens.size() >= t.intValue();
            }
            default -> {
                log.warn("Rule {} has no implementation, ignored", rule.getCode());
                yield false;
            }
        };
    }
}
