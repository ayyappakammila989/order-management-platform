package com.retail.oms.fraud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retail.oms.fraud.domain.CheckKind;
import com.retail.oms.fraud.domain.Decision;
import com.retail.oms.fraud.domain.Evaluation;
import com.retail.oms.fraud.domain.FraudRule;
import com.retail.oms.fraud.domain.RuleEngine;
import com.retail.oms.fraud.domain.RuleScope;
import com.retail.oms.fraud.domain.Subject;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Each rule with edge values (architecture 12.1 "Fraud rules"); same parameters as V2__seed_rules.sql. */
class RuleEngineTest {

    static final List<FraudRule> RULES = List.of(
            FraudRule.of("BLOCKED_CUSTOMER", RuleScope.ANY, 100, null, null),
            FraudRule.of("BLOCKED_ZIP", RuleScope.ORDER, 100, null, null),
            FraudRule.of("HIGH_ORDER_VALUE", RuleScope.ORDER, 50, "1000.00", null),
            FraudRule.of("VERY_HIGH_ORDER_VALUE", RuleScope.ORDER, 80, "5000.00", null),
            FraudRule.of("LARGE_QUANTITY", RuleScope.ORDER, 30, "10", null),
            FraudRule.of("CARD_TESTING", RuleScope.ORDER, 50, "4", 60),
            FraudRule.of("ORDER_VELOCITY", RuleScope.ORDER, 30, "30", 60),
            FraudRule.of("HIGH_REFUND_VALUE", RuleScope.REFUND, 50, "500.00", null),
            FraudRule.of("REFUND_VELOCITY", RuleScope.REFUND, 50, "3", 43200));

    final RuleEngine engine = new RuleEngine(50, 80);
    final FakeHistory history = new FakeHistory();

    @Test
    void cleanOrderIsApprovedWithScoreZero() {
        Evaluation e = engine.evaluate(RULES, order("85.38", 2), history);
        assertThat(e).isEqualTo(new Evaluation(0, Decision.APPROVED, List.of()));
    }

    @Test
    void highOrderValueIsInclusiveAt1000() {
        assertThat(engine.evaluate(RULES, order("999.99", 1), history).decision()).isEqualTo(Decision.APPROVED);
        Evaluation at = engine.evaluate(RULES, order("1000.00", 1), history);
        assertThat(at.reasons()).containsExactly("HIGH_ORDER_VALUE");
        assertThat(at.decision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void veryHighOrderValueIsRejected() {
        assertThat(engine.evaluate(RULES, order("4999.99", 1), history).decision()).isEqualTo(Decision.REVIEW);
        Evaluation e = engine.evaluate(RULES, order("5000.00", 1), history);
        assertThat(e.score()).isEqualTo(130);
        assertThat(e.reasons()).containsExactly("HIGH_ORDER_VALUE", "VERY_HIGH_ORDER_VALUE");
        assertThat(e.decision()).isEqualTo(Decision.REJECTED);
    }

    @Test
    void largeQuantityHitsAtTenUnitsOfOneSku() {
        assertThat(engine.evaluate(RULES, order("50.00", 9), history).reasons()).isEmpty();
        Evaluation ten = engine.evaluate(RULES, order("50.00", 10), history);
        assertThat(ten.reasons()).containsExactly("LARGE_QUANTITY");
        assertThat(ten.decision()).as("30 alone is below review").isEqualTo(Decision.APPROVED);
        // together with a high value: 50 + 30 = 80 = reject
        assertThat(engine.evaluate(RULES, order("1290.00", 10), history).decision()).isEqualTo(Decision.REJECTED);
    }

    @Test
    void blockedCustomerAndZipRejectOnTheirOwn() {
        history.blocked.add("CUSTOMER:bad-guy");
        history.blocked.add("ZIP:99666");
        var badCustomer = new Subject(CheckKind.ORDER, "bad-guy", new BigDecimal("10.00"), "USD", "10001", "pm_1", lines(1));
        var badZip = new Subject(CheckKind.ORDER, "cust-1", new BigDecimal("10.00"), "USD", "99666", "pm_1", lines(1));
        var noZip = new Subject(CheckKind.ORDER, "cust-1", new BigDecimal("10.00"), "USD", null, "pm_1", lines(1));

        assertThat(engine.evaluate(RULES, badCustomer, history).reasons()).containsExactly("BLOCKED_CUSTOMER");
        assertThat(engine.evaluate(RULES, badZip, history).decision()).isEqualTo(Decision.REJECTED);
        assertThat(engine.evaluate(RULES, noZip, history).decision()).isEqualTo(Decision.APPROVED);
        // BLOCKED_CUSTOMER covers refunds too
        assertThat(engine.evaluate(RULES, refund("bad-guy", "10.00"), history).reasons()).containsExactly("BLOCKED_CUSTOMER");
    }

    @Test
    void cardTestingFlagsTheFourthDifferentToken() {
        history.tokens.addAll(Set.of("pm_a", "pm_b"));
        assertThat(engine.evaluate(RULES, withToken("pm_c"), history).reasons()).as("3rd token").isEmpty();
        assertThat(engine.evaluate(RULES, withToken("pm_a"), history).reasons()).as("a token seen before").isEmpty();
        history.tokens.add("pm_c");
        Evaluation fourth = engine.evaluate(RULES, withToken("pm_d"), history);
        assertThat(fourth.reasons()).containsExactly("CARD_TESTING");
        assertThat(fourth.decision()).isEqualTo(Decision.REVIEW);
        assertThat(history.lastWindow).isEqualTo(Duration.ofMinutes(60));
        assertThat(engine.evaluate(RULES, withToken(null), history).reasons()).as("no token, no rule").isEmpty();
    }

    @Test
    void orderVelocityCountsTheCurrentOrderToo() {
        history.checks = 28;
        assertThat(engine.evaluate(RULES, order("10.00", 1), history).reasons()).as("29th").isEmpty();
        history.checks = 29;
        assertThat(engine.evaluate(RULES, order("10.00", 1), history).reasons()).as("30th").containsExactly("ORDER_VELOCITY");
    }

    @Test
    void refundRulesOnlyApplyToRefunds() {
        assertThat(engine.evaluate(RULES, refund("cust-1", "499.99"), history).reasons()).isEmpty();
        assertThat(engine.evaluate(RULES, refund("cust-1", "500.00"), history).reasons()).containsExactly("HIGH_REFUND_VALUE");
        // an order of 500 is not a refund of 500
        assertThat(engine.evaluate(RULES, order("500.00", 1), history).reasons()).isEmpty();

        history.checks = 1;
        assertThat(engine.evaluate(RULES, refund("cust-1", "20.00"), history).reasons()).as("2nd refund").isEmpty();
        history.checks = 2;
        Evaluation third = engine.evaluate(RULES, refund("cust-1", "20.00"), history);
        assertThat(third.reasons()).as("3rd refund in 30 days").containsExactly("REFUND_VELOCITY");
        assertThat(third.decision()).isEqualTo(Decision.REVIEW);
        assertThat(history.lastWindow).isEqualTo(Duration.ofDays(30));
        assertThat(history.lastKind).isEqualTo(CheckKind.REFUND);
    }

    @Test
    void disabledAndUnknownRulesDoNotCount() {
        var rules = List.of(FraudRule.of("HIGH_ORDER_VALUE", RuleScope.ORDER, 50, "1000.00", null).disabled(),
                FraudRule.of("SOMETHING_NEW", RuleScope.ANY, 90, "1", null));
        assertThat(engine.evaluate(rules, order("2000.00", 1), history).decision()).isEqualTo(Decision.APPROVED);
    }

    @Test
    void reviewScoreAboveRejectScoreIsAConfigError() {
        assertThatThrownBy(() -> new RuleEngine(90, 80)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Subject order(String amount, int quantity) {
        return new Subject(CheckKind.ORDER, "cust-1", new BigDecimal(amount), "USD", "10001", "pm_card_visa", lines(quantity));
    }

    private static Subject withToken(String token) {
        return new Subject(CheckKind.ORDER, "cust-1", new BigDecimal("20.00"), "USD", "10001", token, lines(1));
    }

    private static Subject refund(String customer, String amount) {
        return new Subject(CheckKind.REFUND, customer, new BigDecimal(amount), "USD", null, null, List.of());
    }

    private static List<Subject.Line> lines(int quantity) {
        return List.of(new Subject.Line("SKU-1", quantity), new Subject.Line("SKU-2", 1));
    }

    static final class FakeHistory implements RuleEngine.History {
        final Set<String> blocked = new HashSet<>();
        final Set<String> tokens = new HashSet<>();
        int checks;
        Duration lastWindow;
        CheckKind lastKind;

        @Override
        public boolean blocked(String type, String value) {
            return blocked.contains(type + ":" + value);
        }

        @Override
        public int checksSince(CheckKind kind, String customerId, Duration window) {
            lastKind = kind;
            lastWindow = window;
            return checks;
        }

        @Override
        public Set<String> tokensSince(String customerId, Duration window) {
            lastWindow = window;
            return tokens;
        }
    }
}
