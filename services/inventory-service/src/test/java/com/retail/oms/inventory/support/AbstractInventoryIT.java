package com.retail.oms.inventory.support;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of all integration tests: ONE Spring context (one Kafka consumer in the group), see order-query-service.
 * Each test makes its own SKUs so tests do not see each other's stock.
 */
@SpringBootTest
@AutoConfigureObservability
public abstract class AbstractInventoryIT {

    @Autowired protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        Infrastructure.register(registry);
    }

    /** A fresh SKU with the given on-hand count per location, e.g. stock("DC-EAST", 5, "DC-WEST", 3). */
    protected String newSku(Object... locationAndCount) {
        String sku = "IT-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < locationAndCount.length; i += 2) {
            jdbc.update("INSERT INTO stock (sku, location_id, on_hand) VALUES (?, ?, ?)", sku, locationAndCount[i],
                    locationAndCount[i + 1]);
        }
        return sku;
    }

    protected int reserved(String sku, String location) {
        return jdbc.queryForObject("SELECT reserved FROM stock WHERE sku = ? AND location_id = ?", Integer.class, sku, location);
    }

    protected int onHand(String sku, String location) {
        return jdbc.queryForObject("SELECT on_hand FROM stock WHERE sku = ? AND location_id = ?", Integer.class, sku, location);
    }

    protected int totalReserved(String sku) {
        return jdbc.queryForObject("SELECT coalesce(sum(reserved), 0) FROM stock WHERE sku = ?", Integer.class, sku);
    }
}
