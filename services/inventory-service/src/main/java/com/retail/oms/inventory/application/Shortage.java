package com.retail.oms.inventory.application;

/** A line that cannot be reserved: requested more than is available over all locations. */
public record Shortage(String sku, int requested, int available) {

    @Override
    public String toString() {
        return sku + " requested " + requested + ", available " + available;
    }
}
