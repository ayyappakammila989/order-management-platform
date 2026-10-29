package com.retail.oms.inventory.infrastructure.persistence;

import com.retail.oms.inventory.domain.Reservation;
import com.retail.oms.inventory.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @EntityGraph(attributePaths = "lines")
    Optional<Reservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);

    @EntityGraph(attributePaths = "lines")
    List<Reservation> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    @EntityGraph(attributePaths = "lines")
    Optional<Reservation> findWithLinesByReservationId(UUID reservationId);

    /** Expired ACTIVE reservations; SKIP LOCKED so two instances of the expiry job never take the same one. */
    @Query(value = """
            SELECT reservation_id FROM reservation WHERE status = 'ACTIVE' AND expires_at < :now
            ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<UUID> lockExpired(@Param("now") Instant now, @Param("limit") int limit);
}
