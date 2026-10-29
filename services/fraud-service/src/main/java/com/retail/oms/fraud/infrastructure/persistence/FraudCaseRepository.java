package com.retail.oms.fraud.infrastructure.persistence;

import com.retail.oms.fraud.domain.CaseStatus;
import com.retail.oms.fraud.domain.FraudCase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudCaseRepository extends JpaRepository<FraudCase, UUID> {

    Optional<FraudCase> findByCheckId(UUID checkId);

    List<FraudCase> findByStatusOrderByOpenedAtAsc(CaseStatus status, Pageable page);

    List<FraudCase> findByOrderIdOrderByOpenedAtDesc(UUID orderId);
}
