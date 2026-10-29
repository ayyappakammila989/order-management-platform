package com.retail.oms.cart.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedCartRepository extends JpaRepository<SavedCart, UUID> {

    List<SavedCart> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    Optional<SavedCart> findBySavedCartIdAndCustomerId(UUID savedCartId, String customerId);
}
