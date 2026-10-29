package com.retail.oms.fraud.infrastructure.persistence;

import com.retail.oms.fraud.domain.FraudRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudRuleRepository extends JpaRepository<FraudRule, String> {
}
