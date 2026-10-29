package com.retail.oms.inventory.application;

import com.retail.oms.inventory.domain.Location;
import com.retail.oms.inventory.domain.LocationType;
import com.retail.oms.inventory.domain.Stock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Which locations to take stock from, best first: nearest to the ship-to ZIP, distribution centers before stores,
 * then the one with most free stock (fewer splits). "Nearest" is the difference of the 3-digit ZIP prefixes, which run
 * roughly east to west (NJ 088 is next to NY 100; NV 895 is far). A rough stand-in for the real ship-from logic of
 * fulfillment-service (step 4). Pure: no I/O.
 */
public final class LocationChooser {

    private LocationChooser() {
    }

    public record Allocation(Stock stock, int quantity) {
    }

    static int distance(String zip, String locationZip) {
        Integer a = prefix(zip);
        Integer b = prefix(locationZip);
        return a == null || b == null ? 0 : Math.abs(a - b);      // no ZIP: all equally near, the other rules decide
    }

    private static Integer prefix(String zip) {
        return zip != null && zip.length() >= 3 && zip.substring(0, 3).chars().allMatch(Character::isDigit)
                ? Integer.valueOf(zip.substring(0, 3)) : null;
    }

    public static List<Stock> rank(List<Stock> rows, Map<String, Location> locations, String zip) {
        Comparator<Stock> order = Comparator
                .comparingInt((Stock s) -> distance(zip, zipOf(locations, s)))
                .thenComparingInt(s -> typeOf(locations, s) == LocationType.DC ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Stock::available).reversed())
                .thenComparing(Stock::getLocationId);
        List<Stock> sorted = new ArrayList<>(rows);
        sorted.sort(order);
        return sorted;
    }

    /**
     * Takes {@code quantity} from the ranked rows, splitting over locations if needed.
     *
     * @return the allocations, or an empty list if all rows together do not have enough
     */
    public static List<Allocation> allocate(int quantity, List<Stock> ranked) {
        List<Allocation> out = new ArrayList<>();
        int left = quantity;
        for (Stock s : ranked) {
            if (left == 0) {
                break;
            }
            int take = Math.min(left, Math.max(0, s.available()));
            if (take > 0) {
                out.add(new Allocation(s, take));
                left -= take;
            }
        }
        return left == 0 ? out : List.of();
    }

    public static int free(List<Stock> rows) {
        return rows.stream().mapToInt(s -> Math.max(0, s.available())).sum();
    }

    private static String zipOf(Map<String, Location> locations, Stock s) {
        Location l = locations.get(s.getLocationId());
        return l == null ? null : l.getZip();
    }

    private static LocationType typeOf(Map<String, Location> locations, Stock s) {
        Location l = locations.get(s.getLocationId());
        return l == null ? LocationType.STORE : l.getType();
    }
}
