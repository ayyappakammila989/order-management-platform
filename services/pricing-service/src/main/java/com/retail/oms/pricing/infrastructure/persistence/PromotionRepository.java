package com.retail.oms.pricing.infrastructure.persistence;

import com.retail.oms.pricing.domain.Promotion;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    @Query("select p from Promotion p where p.active = true and p.startsAt <= :now and p.endsAt > :now order by p.id")
    List<Promotion> findLive(@Param("now") Instant now);
}
