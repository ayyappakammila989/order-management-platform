package com.retail.oms.inventory.application;

import java.util.Optional;

/** atp:{sku}:{zip} (architecture 8). Only an accelerator: a failing cache behaves like an empty one. */
public interface AvailabilityCache {

    Optional<Availability> get(String sku, String zip);

    void put(Availability availability);

    /** Drops every ZIP entry of this SKU. */
    void evict(String sku);
}
