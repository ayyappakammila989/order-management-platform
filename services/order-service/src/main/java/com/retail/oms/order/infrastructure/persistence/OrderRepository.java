package com.retail.oms.order.infrastructure.persistence;

import com.retail.oms.order.domain.Order;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Order details in one query: no N+1 for lines and addresses (architecture 13). */
    @EntityGraph(attributePaths = {"lines", "addresses"})
    @Query("select o from Order o where o.orderId = :orderId")
    Optional<Order> findDetailsById(UUID orderId);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /** Only the owner column, for access checks without loading the order. */
    @Query("select o.customerId from Order o where o.orderId = :orderId")
    Optional<String> findCustomerIdById(UUID orderId);

    @Query(value = "select nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumber();
}
