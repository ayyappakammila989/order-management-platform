package com.retail.oms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Stock of one SKU at one location. Reserving moves quantity from "free" to {@code reserved}; on_hand only changes
 * with a stock snapshot (and, in step 4, with a shipment). {@code version} is the optimistic lock: Hibernate adds
 * {@code WHERE version = ?} to every UPDATE, so a write based on a stale read fails instead of overwriting.
 */
@Entity
@Table(name = "stock")
@IdClass(StockId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock {

    @Id
    private String sku;

    @Id
    @Column(name = "location_id")
    private String locationId;

    @Column(name = "on_hand")
    private int onHand;

    private int reserved;

    @Column(name = "safety_stock")
    private int safetyStock;

    @Version
    private long version;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Stock(String sku, String locationId, int onHand, int safetyStock, Instant now) {
        this.sku = sku;
        this.locationId = locationId;
        this.onHand = onHand;
        this.safetyStock = safetyStock;
        this.updatedAt = now;
    }

    /** What can still be promised: on hand minus reserved minus the safety stock. Negative after an oversell. */
    public int available() {
        return onHand - reserved - safetyStock;
    }

    public void reserve(int quantity, Instant now) {
        if (quantity <= 0 || quantity > available()) {
            throw new IllegalArgumentException("Cannot reserve " + quantity + " of " + sku + " at " + locationId
                    + ", available " + available());
        }
        reserved += quantity;
        updatedAt = now;
    }

    /** Never below zero: releasing more than is reserved means the books were already off, not a reason to fail. */
    public void release(int quantity, Instant now) {
        reserved = Math.max(0, reserved - quantity);
        updatedAt = now;
    }

    /** Absolute count from a warehouse/store snapshot. Reservations stay: those units are still on the shelf. */
    public void setOnHand(int count, Instant now) {
        if (count < 0) {
            throw new IllegalArgumentException("on_hand cannot be negative: " + count);
        }
        onHand = count;
        updatedAt = now;
    }
}
