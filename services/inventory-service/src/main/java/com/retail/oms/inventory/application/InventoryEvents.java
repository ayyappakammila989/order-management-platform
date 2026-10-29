package com.retail.oms.inventory.application;

import com.retail.oms.events.Topics;
import com.retail.oms.events.inventory.InventoryChangedEvent;
import com.retail.oms.events.inventory.LocationQuantity;
import com.retail.oms.events.inventory.StockReleasedEvent;
import com.retail.oms.events.inventory.StockReserveFailedEvent;
import com.retail.oms.events.inventory.StockReservedEvent;
import com.retail.oms.events.inventory.StockShortage;
import com.retail.oms.inventory.domain.Reservation;
import com.retail.oms.inventory.domain.Stock;
import com.retail.oms.kafka.outbox.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes inventory-service's messages to the outbox, inside the caller's transaction: replies to the saga
 * (keyed by orderId) and inventory.changed facts (keyed by SKU). After the commit it drops the availability cache
 * of the SKUs that changed.
 */
@Component
public class InventoryEvents {

    private final Outbox outbox;
    private final AvailabilityCache cache;
    private final Clock clock;

    public InventoryEvents(Outbox outbox, AvailabilityCache cache, Clock clock) {
        this.outbox = outbox;
        this.cache = cache;
        this.clock = clock;
    }

    public void reserved(SagaContext ctx, Reservation r) {
        String id = UUID.randomUUID().toString();
        var event = StockReservedEvent.newBuilder().setEventId(id).setOccurredAt(Instant.now(clock))
                .setCommandId(ctx.commandId()).setSagaId(ctx.sagaId()).setOrderId(r.getOrderId().toString())
                .setReservationId(r.getReservationId().toString()).setExpiresAt(r.getExpiresAt())
                .setLines(lines(r)).build();
        outbox.add(Topics.INVENTORY_RESERVED, r.getOrderId().toString(), id, event, ctx.correlationId());
    }

    public void reserveFailed(SagaContext ctx, UUID orderId, List<Shortage> shortages) {
        String id = UUID.randomUUID().toString();
        var event = StockReserveFailedEvent.newBuilder().setEventId(id).setOccurredAt(Instant.now(clock))
                .setCommandId(ctx.commandId()).setSagaId(ctx.sagaId()).setOrderId(orderId.toString())
                .setReason("Not enough stock: " + shortages)
                .setShortages(shortages.stream().map(s -> new StockShortage(s.sku(), s.requested(), s.available())).toList())
                .build();
        outbox.add(Topics.INVENTORY_RESERVE_FAILED, orderId.toString(), id, event, ctx.correlationId());
    }

    /** @param r the released reservation, or null when a release command found nothing to release */
    public void released(SagaContext ctx, UUID orderId, Reservation r, String reason) {
        String id = UUID.randomUUID().toString();
        var event = StockReleasedEvent.newBuilder().setEventId(id).setOccurredAt(Instant.now(clock))
                .setCommandId(ctx.commandId()).setSagaId(ctx.sagaId()).setOrderId(orderId.toString())
                .setReservationId(r == null ? null : r.getReservationId().toString())
                .setReason(reason).setLines(r == null ? List.of() : lines(r)).build();
        outbox.add(Topics.INVENTORY_RELEASED, orderId.toString(), id, event, ctx.correlationId());
    }

    public void changed(Stock s, String cause, String correlationId) {
        String id = UUID.randomUUID().toString();
        var event = InventoryChangedEvent.newBuilder().setEventId(id).setOccurredAt(Instant.now(clock))
                .setSku(s.getSku()).setLocationId(s.getLocationId()).setOnHand(s.getOnHand())
                .setReserved(s.getReserved()).setSafetyStock(s.getSafetyStock()).setAvailable(s.available())
                .setCause(cause).build();
        outbox.add(Topics.INVENTORY_CHANGED, s.getSku(), id, event, correlationId);
        evictAfterCommit(s.getSku());
    }

    private void evictAfterCommit(String sku) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.evict(sku);
                }
            });
        } else {
            cache.evict(sku);
        }
    }

    private static List<LocationQuantity> lines(Reservation r) {
        return r.getLines().stream()
                .map(l -> new LocationQuantity(l.getSku(), l.getLocationId(), l.getQuantity())).toList();
    }
}
