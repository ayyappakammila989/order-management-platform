package com.retail.oms.inventory.domain;

/** ACTIVE holds stock; RELEASED gave it back (cancel, compensation, expiry); CONSUMED = shipped (step 4). */
public enum ReservationStatus {
    ACTIVE,
    RELEASED,
    CONSUMED
}
