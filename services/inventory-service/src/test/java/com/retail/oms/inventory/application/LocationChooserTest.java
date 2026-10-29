package com.retail.oms.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.retail.oms.inventory.domain.Location;
import com.retail.oms.inventory.domain.LocationType;
import com.retail.oms.inventory.domain.Stock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocationChooserTest {

    static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    static final Map<String, Location> LOCATIONS = Map.of(
            "DC-EAST", new Location("DC-EAST", LocationType.DC, "Edison", "08817"),
            "DC-WEST", new Location("DC-WEST", LocationType.DC, "Reno", "89501"),
            "STORE-NYC", new Location("STORE-NYC", LocationType.STORE, "NYC", "10001"));

    static Stock stock(String location, int onHand) {
        return new Stock("SKU-1", location, onHand, 0, NOW);
    }

    @Test
    void nearestFirstThenDcBeforeStoreThenMostStock() {
        var rows = List.of(stock("DC-WEST", 50), stock("STORE-NYC", 5), stock("DC-EAST", 10));

        assertThat(LocationChooser.rank(rows, LOCATIONS, "10001")).extracting(Stock::getLocationId)
                .containsExactly("STORE-NYC", "DC-EAST", "DC-WEST");     // prefix 100: 0, 12 (088), 795 (895) away
        assertThat(LocationChooser.rank(rows, LOCATIONS, "89523")).extracting(Stock::getLocationId)
                .containsExactly("DC-WEST", "STORE-NYC", "DC-EAST");     // 895: 0, 795, 807 away
        assertThat(LocationChooser.rank(rows, LOCATIONS, null)).extracting(Stock::getLocationId)
                .containsExactly("DC-WEST", "DC-EAST", "STORE-NYC");     // no ZIP: DCs, most stock first
    }

    @Test
    void equallyFarLocationsPreferADistributionCenter() {
        var rows = List.of(new Stock("SKU-1", "STORE-NYC", 50, 0, NOW), new Stock("SKU-1", "DC-EAST", 10, 0, NOW));
        var sameZip = Map.of("DC-EAST", new Location("DC-EAST", LocationType.DC, "DC", "10002"),
                "STORE-NYC", new Location("STORE-NYC", LocationType.STORE, "NYC", "10001"));

        assertThat(LocationChooser.rank(rows, sameZip, "10001")).extracting(Stock::getLocationId)
                .containsExactly("DC-EAST", "STORE-NYC");                // same prefix: the DC, although it has less
    }

    @Test
    void splitsOverLocationsInRankOrder() {
        var ranked = List.of(stock("STORE-NYC", 3), stock("DC-EAST", 10));

        var allocation = LocationChooser.allocate(5, ranked);

        assertThat(allocation).extracting(a -> a.stock().getLocationId() + ":" + a.quantity())
                .containsExactly("STORE-NYC:3", "DC-EAST:2");
    }

    @Test
    void notEnoughOverAllLocationsGivesNothing() {
        assertThat(LocationChooser.allocate(14, List.of(stock("STORE-NYC", 3), stock("DC-EAST", 10)))).isEmpty();
        assertThat(LocationChooser.free(List.of(stock("STORE-NYC", 3), stock("DC-EAST", 10)))).isEqualTo(13);
    }

    @Test
    void safetyStockIsNotPromised() {
        var store = new Stock("SKU-1", "STORE-NYC", 10, 2, NOW);       // 2 kept back
        assertThat(store.available()).isEqualTo(8);
        assertThat(LocationChooser.allocate(9, List.of(store))).isEmpty();
        assertThat(LocationChooser.allocate(8, List.of(store))).hasSize(1);
    }
}
