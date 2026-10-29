package com.retail.oms.inventory.application;

import com.retail.oms.events.inventory.StockLevel;
import com.retail.oms.events.inventory.StockSnapshotEvent;
import com.retail.oms.inventory.domain.Stock;
import com.retail.oms.inventory.domain.StockId;
import com.retail.oms.inventory.infrastructure.persistence.LocationRepository;
import com.retail.oms.inventory.infrastructure.persistence.SnapshotState;
import com.retail.oms.inventory.infrastructure.persistence.StockRepository;
import com.retail.oms.kafka.outbox.ProcessedMessages;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Applies a stock snapshot (architecture 3.3 step 4): absolute on-hand counts for one location, from a warehouse
 * SFTP file or the store POS. For now the snapshots come as Kafka events from a test producer.
 * <p>
 * No row locks here: the rows are read normally and written with an optimistic version check. If a reservation
 * changed one of them in between, the whole snapshot is re-applied from a fresh read ({@link OptimisticRetry}).
 * Reserved quantities are kept: reserved units are still physically on the shelf until they ship.
 */
@Service
@Slf4j
public class SnapshotService {

    public enum Outcome { APPLIED, STALE, DUPLICATE }

    private final StockRepository stock;
    private final LocationRepository locations;
    private final SnapshotState state;
    private final ProcessedMessages processed;
    private final InventoryEvents events;
    private final OptimisticRetry retry;
    private final EntityManager em;
    private final Clock clock;

    public SnapshotService(StockRepository stock, LocationRepository locations, SnapshotState state,
            ProcessedMessages processed, InventoryEvents events, OptimisticRetry retry, EntityManager em, Clock clock) {
        this.stock = stock;
        this.locations = locations;
        this.state = state;
        this.processed = processed;
        this.events = events;
        this.retry = retry;
        this.em = em;
        this.clock = clock;
    }

    public Outcome apply(StockSnapshotEvent snapshot, String correlationId) {
        return retry.inTransaction("snapshot " + snapshot.getSnapshotId(), () -> doApply(snapshot, correlationId));
    }

    private Outcome doApply(StockSnapshotEvent snapshot, String correlationId) {
        if (!processed.markProcessed(ReservationService.CONSUMER, snapshot.getSnapshotId())) {
            return Outcome.DUPLICATE;
        }
        String locationId = snapshot.getLocationId();
        if (!locations.existsById(locationId)) {
            throw new InvalidMessageException("Snapshot " + snapshot.getSnapshotId() + " is for unknown location " + locationId);
        }
        if (!state.claimIfNewer(locationId, snapshot.getSnapshotId(), snapshot.getTakenAt())) {
            log.info("Snapshot {} for {} taken at {} is older than the last one applied, ignored",
                    snapshot.getSnapshotId(), locationId, snapshot.getTakenAt());
            return Outcome.STALE;
        }
        Instant now = Instant.now(clock);
        List<Stock> changed = new ArrayList<>();
        for (StockLevel item : snapshot.getItems()) {
            if (item.getOnHand() < 0) {
                throw new InvalidMessageException("Snapshot " + snapshot.getSnapshotId() + ": negative count for " + item.getSku());
            }
            Stock row = stock.findById(new StockId(item.getSku(), locationId)).orElse(null);
            if (row == null) {
                row = stock.save(new Stock(item.getSku(), locationId, item.getOnHand(), 0, now));
                changed.add(row);
            } else if (row.getOnHand() != item.getOnHand()) {
                row.setOnHand(item.getOnHand(), now);
                changed.add(row);
                if (row.available() < 0) {
                    log.warn("{}@{}: snapshot says {} on hand but {} are reserved (oversold by {})", row.getSku(),
                            locationId, row.getOnHand(), row.getReserved(), -row.available());
                }
            }
        }
        em.flush();       // version check: a reservation that touched one of these rows meanwhile makes this fail
        changed.forEach(s -> events.changed(s, "SNAPSHOT", correlationId));
        log.info("Snapshot {} ({}) for {}: {} items, {} changed", snapshot.getSnapshotId(), snapshot.getSource(),
                locationId, snapshot.getItems().size(), changed.size());
        return Outcome.APPLIED;
    }
}
