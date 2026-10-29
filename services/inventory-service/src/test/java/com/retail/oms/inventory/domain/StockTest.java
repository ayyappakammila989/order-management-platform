package com.retail.oms.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Architecture 12.1: reserve fails when on_hand - reserved < requested; release restores the quantity. */
class StockTest {

    static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    void reserveFailsWhenNotEnoughIsFree() {
        var s = new Stock("SKU-1", "DC-EAST", 5, 0, NOW);
        s.reserve(3, NOW);

        assertThat(s.available()).isEqualTo(2);
        assertThatThrownBy(() -> s.reserve(3, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.reserve(0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(s.getReserved()).isEqualTo(3);
    }

    @Test
    void releaseRestoresTheQuantityAndNeverGoesBelowZero() {
        var s = new Stock("SKU-1", "DC-EAST", 5, 0, NOW);
        s.reserve(3, NOW);
        s.release(3, NOW);
        assertThat(s.available()).isEqualTo(5);

        s.release(2, NOW);
        assertThat(s.getReserved()).isZero();
    }

    @Test
    void snapshotKeepsReservationsAndMayLeaveTheRowOversold() {
        var s = new Stock("SKU-1", "DC-EAST", 5, 0, NOW);
        s.reserve(4, NOW);
        s.setOnHand(3, NOW);

        assertThat(s.getReserved()).isEqualTo(4);
        assertThat(s.available()).isEqualTo(-1);
        assertThatThrownBy(() -> s.setOnHand(-1, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservationCanOnlyBeReleasedOnce() {
        var r = new Reservation(java.util.UUID.randomUUID(), "saga-1", NOW.plusSeconds(60), NOW);
        r.release(ReleaseReason.COMPENSATION, NOW);

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThatThrownBy(() -> r.release(ReleaseReason.EXPIRED, NOW)).isInstanceOf(IllegalStateException.class);
    }
}
