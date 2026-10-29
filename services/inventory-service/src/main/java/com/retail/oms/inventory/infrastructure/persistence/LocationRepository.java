package com.retail.oms.inventory.infrastructure.persistence;

import com.retail.oms.inventory.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, String> {
}
