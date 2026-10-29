package com.retail.oms.fraud.infrastructure.persistence;

import com.retail.oms.fraud.domain.CheckKind;
import com.retail.oms.fraud.domain.FraudCheck;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FraudCheckRepository extends JpaRepository<FraudCheck, UUID> {

    Optional<FraudCheck> findByCommandId(String commandId);

    List<FraudCheck> findByOrderIdAndKindOrderByCreatedAtDesc(UUID orderId, CheckKind kind);

    int countByKindAndCustomerIdAndCreatedAtGreaterThanEqual(CheckKind kind, String customerId, Instant since);

    @Query("""
            select distinct c.paymentToken from FraudCheck c
            where c.customerId = :customerId and c.kind = com.retail.oms.fraud.domain.CheckKind.ORDER
              and c.paymentToken is not null and c.createdAt >= :since""")
    List<String> tokensSince(String customerId, Instant since);
}
