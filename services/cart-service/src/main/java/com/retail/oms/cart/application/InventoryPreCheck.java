package com.retail.oms.cart.application;

import com.retail.oms.cart.config.InventoryCheckProperties;
import com.retail.oms.cart.domain.Cart;
import com.retail.oms.cart.exception.OutOfStockException;
import com.retail.oms.cart.infrastructure.client.InventoryClient;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Architecture 3.1 step 4: a quick availability check before the order is created, so a customer hears "only 2 left"
 * now instead of a cancellation e-mail later. It is a hint, not a reservation: the saga reserves for real.
 * <p>
 * Resilience (architecture, cart -> inventory): retry 2 times on technical errors, behind the Feign circuit breaker.
 * If inventory-service cannot answer, checkout is ALLOWED (metric oms.cart.inventory_check.skipped); the saga
 * re-checks and cancels the order if the stock really is not there. Only a definite "not enough" stops checkout.
 */
@Component
@Slf4j
public class InventoryPreCheck {

    private final InventoryClient inventory;
    private final InventoryCheckProperties props;
    private final Retry retry;
    private final Counter skipped;
    private final Counter rejected;

    public InventoryPreCheck(InventoryClient inventory, InventoryCheckProperties props, MeterRegistry meters) {
        this.inventory = inventory;
        this.props = props;
        this.retry = Retry.of("inventory-precheck", RetryConfig.custom()
                .maxAttempts(props.retries() + 1).waitDuration(props.retryWait()).build());
        this.skipped = meters.counter("oms.cart.inventory_check.skipped");
        this.rejected = meters.counter("oms.cart.inventory_check.rejected");
    }

    /** @throws OutOfStockException if inventory-service says a SKU does not have the quantity */
    public void check(Cart cart, String shipToZip) {
        if (!props.enabled()) {
            return;
        }
        Map<String, Integer> demand = new TreeMap<>();
        cart.items().forEach(i -> demand.merge(i.sku(), i.quantity(), Integer::sum));
        List<String> shortages = new ArrayList<>();
        for (var entry : demand.entrySet()) {
            InventoryClient.Availability a;
            try {
                a = retry.executeSupplier(() -> inventory.availability(entry.getKey(), shipToZip));
            } catch (RuntimeException e) {
                skipped.increment();
                log.warn("Inventory pre-check skipped for cart {} (the saga re-checks): {}", cart.cartId(),
                        e.getCause() != null ? e.getCause().toString() : e.toString());
                return;
            }
            if (a.available() < entry.getValue()) {
                shortages.add(entry.getKey() + " (requested " + entry.getValue() + ", available " + a.available() + ")");
            }
        }
        if (!shortages.isEmpty()) {
            rejected.increment();
            throw new OutOfStockException("Not enough stock: " + String.join(", ", shortages));
        }
    }
}
