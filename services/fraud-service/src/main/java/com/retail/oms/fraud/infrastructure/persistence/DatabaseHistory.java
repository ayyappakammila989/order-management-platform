package com.retail.oms.fraud.infrastructure.persistence;

import com.retail.oms.fraud.domain.CheckKind;
import com.retail.oms.fraud.domain.RuleEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@link RuleEngine.History} read from fraud_check and fraud_blocklist. */
@Component
public class DatabaseHistory implements RuleEngine.History {

    private final JdbcTemplate jdbc;
    private final FraudCheckRepository checks;
    private final Clock clock;

    public DatabaseHistory(JdbcTemplate jdbc, FraudCheckRepository checks, Clock clock) {
        this.jdbc = jdbc;
        this.checks = checks;
        this.clock = clock;
    }

    @Override
    public boolean blocked(String type, String value) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM fraud_blocklist WHERE type = ? AND value = ?)", Boolean.class, type, value));
    }

    @Override
    public int checksSince(CheckKind kind, String customerId, Duration window) {
        return checks.countByKindAndCustomerIdAndCreatedAtGreaterThanEqual(kind, customerId, since(window));
    }

    @Override
    public Set<String> tokensSince(String customerId, Duration window) {
        return new HashSet<>(checks.tokensSince(customerId, since(window)));
    }

    private Instant since(Duration window) {
        return Instant.now(clock).minus(window);
    }
}
