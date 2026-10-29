package com.retail.oms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Quantity of one SKU taken from one location. A line of the order may be split over several locations. */
@Entity
@Table(name = "reservation_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    private String sku;

    @Column(name = "location_id")
    private String locationId;

    private int quantity;

    ReservationLine(Reservation reservation, String sku, String locationId, int quantity) {
        this.reservation = reservation;
        this.sku = sku;
        this.locationId = locationId;
        this.quantity = quantity;
    }
}
