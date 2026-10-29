package com.retail.oms.orchestrator.infrastructure.persistence;

import com.retail.oms.orchestrator.domain.SagaInstance;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

    Optional<SagaInstance> findByOrderId(UUID orderId);

    /** Sagas waiting past their deadline; SKIP LOCKED so two orchestrator instances never take the same one. */
    @Query(value = """
            SELECT saga_id FROM saga_instance WHERE status IN ('RUNNING', 'COMPENSATING') AND deadline_at < :now
            ORDER BY deadline_at LIMIT :limit FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<UUID> lockOverdue(@Param("now") Instant now, @Param("limit") int limit);
}
