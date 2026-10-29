package com.retail.oms.order.infrastructure.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByAggregateIdOrderByCreatedAt(String aggregateId);

    @Modifying
    @Query("delete from OutboxEvent e where e.createdAt < :before")
    int deleteOlderThan(Instant before);
}
