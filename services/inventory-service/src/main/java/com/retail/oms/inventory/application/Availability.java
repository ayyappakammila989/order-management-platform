package com.retail.oms.inventory.application;

import java.util.List;

/**
 * Available-to-promise for one SKU, seen from a ship-to ZIP (nearest locations first). {@code available} sums the
 * free stock of all locations (never negative per location).
 */
public record Availability(String sku, String zip, int available, List<LocationAvailability> locations) {

    public record LocationAvailability(String locationId, String type, int available) {
    }
}
