package com.retail.oms.orchestrator.infrastructure.persistence;

import com.retail.oms.orchestrator.domain.SagaStepLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaStepLogRepository extends JpaRepository<SagaStepLog, Long> {

    List<SagaStepLog> findBySagaIdOrderByIdAsc(UUID sagaId);
}
