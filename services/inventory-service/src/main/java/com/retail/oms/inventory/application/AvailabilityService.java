package com.retail.oms.inventory.application;

import com.retail.oms.inventory.domain.Location;
import com.retail.oms.inventory.domain.Stock;
import com.retail.oms.inventory.infrastructure.persistence.LocationRepository;
import com.retail.oms.inventory.infrastructure.persistence.StockRepository;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Available-to-promise for the cart pre-check and the apps (architecture 3.1 step 4). A quick answer, not a promise:
 * stock can be gone a second later, which is why the saga reserves again. Cached 30 s, dropped on every change.
 */
@Service
public class AvailabilityService {

    private final StockRepository stock;
    private final LocationRepository locations;
    private final AvailabilityCache cache;

    public AvailabilityService(StockRepository stock, LocationRepository locations, AvailabilityCache cache) {
        this.stock = stock;
        this.locations = locations;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public Availability availability(String sku, String zip) {
        return cache.get(sku, zip).orElseGet(() -> {
            Availability fresh = compute(sku, zip);
            cache.put(fresh);
            return fresh;
        });
    }

    private Availability compute(String sku, String zip) {
        Map<String, Location> byId = locations.findAll().stream()
                .collect(Collectors.toMap(Location::getLocationId, Function.identity()));
        var rows = LocationChooser.rank(stock.findBySkuOrderByLocationId(sku), byId, zip);
        var perLocation = rows.stream()
                .map(s -> new Availability.LocationAvailability(s.getLocationId(),
                        byId.containsKey(s.getLocationId()) ? byId.get(s.getLocationId()).getType().name() : "UNKNOWN",
                        Math.max(0, s.available())))
                .toList();
        return new Availability(sku, zip, rows.stream().mapToInt(Stock::available).map(a -> Math.max(0, a)).sum(), perLocation);
    }
}
