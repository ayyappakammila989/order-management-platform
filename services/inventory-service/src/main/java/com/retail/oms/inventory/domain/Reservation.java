package com.retail.oms.inventory.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Stock held for one order. {@code version} (optimistic lock) decides races between the ways a reservation can end:
 * a release command, the order being cancelled and the expiry job. The first commit wins; the others fail their
 * {@code UPDATE ... WHERE version = ?}, retry, and then see that it is no longer ACTIVE.
 */
@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "saga_id")
    private String sagaId;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_reason")
    private ReleaseReason releaseReason;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sku, locationId")
    private List<ReservationLine> lines = new ArrayList<>();

    public Reservation(UUID orderId, String sagaId, Instant expiresAt, Instant now) {
        this.reservationId = UUID.randomUUID();
        this.orderId = orderId;
        this.sagaId = sagaId;
        this.status = ReservationStatus.ACTIVE;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void addLine(String sku, String locationId, int quantity) {
        lines.add(new ReservationLine(this, sku, locationId, quantity));
    }

    public List<ReservationLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public boolean isActive() {
        return status == ReservationStatus.ACTIVE;
    }

    public void release(ReleaseReason reason, Instant now) {
        if (!isActive()) {
            throw new IllegalStateException("Reservation " + reservationId + " is " + status);
        }
        status = ReservationStatus.RELEASED;
        releaseReason = reason;
        updatedAt = now;
    }

    /** The order is confirmed: keep the stock until shipment or cancellation, no expiry any more. */
    public void keepUntilShipment(Instant now) {
        expiresAt = null;
        updatedAt = now;
    }
}
