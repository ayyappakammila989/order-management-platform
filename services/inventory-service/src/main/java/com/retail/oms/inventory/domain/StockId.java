package com.retail.oms.inventory.domain;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Composite key of {@link Stock}: one row per SKU and location. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class StockId implements Serializable {

    private String sku;
    private String locationId;
}
