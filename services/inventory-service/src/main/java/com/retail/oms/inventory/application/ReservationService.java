package com.retail.oms.inventory.application;

import com.retail.oms.inventory.config.InventoryProperties;
import com.retail.oms.inventory.domain.Location;
import com.retail.oms.inventory.domain.ReleaseReason;
import com.retail.oms.inventory.domain.Reservation;
import com.retail.oms.inventory.domain.ReservationLine;
import com.retail.oms.inventory.domain.ReservationStatus;
import com.retail.oms.inventory.domain.Stock;
import com.retail.oms.inventory.infrastructure.persistence.LocationRepository;
import com.retail.oms.inventory.infrastructure.persistence.ReservationRepository;
import com.retail.oms.inventory.infrastructure.persistence.StockRepository;
import com.retail.oms.kafka.outbox.ProcessedMessages;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Reserve and release stock (architecture 3.3). Every public method is one short transaction (retried on an
 * optimistic-lock conflict) that changes stock, records the message as processed and writes the reply and
 * inventory.changed events to the outbox - all or nothing.
 */
@Service
@Slf4j
public class ReservationService {

    public static final String CONSUMER = "inventory-service";

    public sealed interface ReserveOutcome {
        record Reserved(Reservation reservation, boolean existing) implements ReserveOutcome {
        }

        record Rejected(List<Shortage> shortages) implements ReserveOutcome {
        }

        record Duplicate() implements ReserveOutcome {
        }
    }

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final LocationRepository locations;
    private final ProcessedMessages processed;
    private final InventoryEvents events;
    private final OptimisticRetry retry;
    private final EntityManager em;
    private final InventoryProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public ReservationService(StockRepository stock, ReservationRepository reservations, LocationRepository locations,
            ProcessedMessages processed, InventoryEvents events, OptimisticRetry retry, EntityManager em,
            InventoryProperties props, Clock clock, MeterRegistry meters) {
        this.stock = stock;
        this.reservations = reservations;
        this.locations = locations;
        this.processed = processed;
        this.events = events;
        this.retry = retry;
        this.em = em;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Reserves all lines or nothing. For a saga command the reply (reserved / reserve-failed) goes to the outbox in
     * the same transaction; a repeated command id is a {@link ReserveOutcome.Duplicate} (its reply was sent before).
     * A second reserve for an order that already holds an ACTIVE reservation returns that reservation.
     */
    public ReserveOutcome reserve(ReserveRequest request, SagaContext ctx) {
        return retry.inTransaction("reserve " + request.orderId(), () -> doReserve(request, ctx));
    }

    private ReserveOutcome doReserve(ReserveRequest request, SagaContext ctx) {
        if (ctx.isCommand() && !processed.markProcessed(CONSUMER, ctx.commandId())) {
            log.info("Reserve command {} for order {} was handled before, skipping", ctx.commandId(), request.orderId());
            return new ReserveOutcome.Duplicate();
        }
        Optional<Reservation> existing = reservations.findByOrderIdAndStatus(request.orderId(), ReservationStatus.ACTIVE);
        if (existing.isPresent()) {
            log.info("Order {} already holds reservation {}", request.orderId(), existing.get().getReservationId());
            if (ctx.isCommand()) {
                events.reserved(ctx, existing.get());
            }
            return new ReserveOutcome.Reserved(existing.get(), true);
        }

        Map<String, Location> locationById = locations.findAll().stream()
                .collect(Collectors.toMap(Location::getLocationId, Function.identity()));
        // the same SKU on two order lines is one demand; SKUs in a fixed order so that waiting locks cannot deadlock
        Map<String, Integer> demand = new TreeMap<>();
        request.lines().forEach(l -> demand.merge(l.sku(), l.quantity(), Integer::sum));

        Map<String, List<LocationChooser.Allocation>> plan = new LinkedHashMap<>();
        List<Shortage> shortages = new ArrayList<>();
        for (var entry : demand.entrySet()) {
            String sku = entry.getKey();
            int quantity = entry.getValue();
            List<Stock> rows = stock.lockFreeRowsSkipLocked(sku);
            List<LocationChooser.Allocation> allocation =
                    LocationChooser.allocate(quantity, LocationChooser.rank(rows, locationById, request.shipToZip()));
            if (allocation.isEmpty()) {
                // not enough in the rows we could lock at once: wait for the skipped ones (bounded by lock_timeout)
                meters.counter("oms.inventory.reserve_second_pass").increment();
                setLockTimeout();
                rows = stock.lockAllRows(sku);
                allocation = LocationChooser.allocate(quantity, LocationChooser.rank(rows, locationById, request.shipToZip()));
                if (allocation.isEmpty()) {
                    shortages.add(new Shortage(sku, quantity, LocationChooser.free(rows)));
                    continue;
                }
            }
            plan.put(sku, allocation);
        }

        if (!shortages.isEmpty()) {
            meters.counter("oms.inventory.reservations", "outcome", "rejected").increment();
            log.info("Order {}: not enough stock: {}", request.orderId(), shortages);
            if (ctx.isCommand()) {
                events.reserveFailed(ctx, request.orderId(), shortages);
            }
            return new ReserveOutcome.Rejected(shortages);
        }

        Instant now = Instant.now(clock);
        var reservation = new Reservation(request.orderId(), ctx.sagaId(), now.plus(props.reservationTtl()), now);
        plan.values().stream().flatMap(List::stream).forEach(a -> {
            a.stock().reserve(a.quantity(), now);
            reservation.addLine(a.stock().getSku(), a.stock().getLocationId(), a.quantity());
        });
        reservations.save(reservation);
        em.flush();       // the version checks and the unique "one ACTIVE per order" index fire here, inside the retry
        plan.values().stream().flatMap(List::stream)
                .forEach(a -> events.changed(a.stock(), "RESERVED", ctx.correlationId()));
        if (ctx.isCommand()) {
            events.reserved(ctx, reservation);
        }
        meters.counter("oms.inventory.reservations", "outcome", "reserved").increment();
        log.info("Order {}: reserved {} as {}", request.orderId(), describe(reservation), reservation.getReservationId());
        return new ReserveOutcome.Reserved(reservation, false);
    }

    /**
     * Releases the ACTIVE reservation of an order. For a saga command a reply is always written, also when there was
     * nothing to release (already released by the order cancellation, or never reserved), so the saga can go on.
     *
     * @return the released reservation; empty if there was nothing to release or the message was a duplicate
     */
    public Optional<Reservation> releaseForOrder(UUID orderId, ReleaseReason reason, SagaContext ctx, String messageId) {
        return retry.inTransaction("release " + orderId, () -> {
            if (messageId != null && !processed.markProcessed(CONSUMER, messageId)) {
                log.info("Release message {} for order {} was handled before, skipping", messageId, orderId);
                return Optional.empty();
            }
            Optional<Reservation> active = reservations.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE);
            active.ifPresent(r -> release(r, reason, ctx));
            // every release is a published fact; a command also gets an answer when there was nothing to release
            if (active.isPresent() || ctx.isCommand()) {
                events.released(ctx, orderId, active.orElse(null), active.isPresent() ? reason.name() : "NOTHING_TO_RELEASE");
            }
            if (active.isEmpty()) {
                log.info("Order {}: nothing to release ({})", orderId, reason);
            }
            return active;
        });
    }

    /** DELETE /internal/v1/reservations/{id}: idempotent, an already released reservation is returned as it is. */
    public Reservation releaseById(UUID reservationId, ReleaseReason reason, String correlationId) {
        return retry.inTransaction("release " + reservationId, () -> {
            Reservation r = reservations.findWithLinesByReservationId(reservationId)
                    .orElseThrow(() -> new ReservationNotFoundException(reservationId.toString()));
            if (r.isActive()) {
                release(r, reason, SagaContext.none(correlationId));
                events.released(SagaContext.none(correlationId), r.getOrderId(), r, reason.name());
            }
            return r;
        });
    }

    /** oms.order.confirmed: keep the stock until shipment or cancellation (no expiry). */
    public void keepUntilShipment(UUID orderId, String messageId) {
        retry.inTransaction("confirm " + orderId, () -> {
            if (!processed.markProcessed(CONSUMER, messageId)) {
                return null;
            }
            reservations.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE).ifPresentOrElse(r -> {
                r.keepUntilShipment(Instant.now(clock));
                log.info("Order {} confirmed: reservation {} kept until shipment", orderId, r.getReservationId());
            }, () -> log.warn("Order {} confirmed but holds no ACTIVE reservation", orderId));
            return null;
        });
    }

    /**
     * Releases ACTIVE reservations whose expiry has passed (orders that were never confirmed, e.g. a stuck saga).
     * @return how many were released in this batch
     */
    public int expireBatch() {
        return retry.inTransaction("expire", () -> {
            Instant now = Instant.now(clock);
            List<UUID> ids = reservations.lockExpired(now, props.expiryBatchSize());
            for (UUID id : ids) {
                Reservation r = reservations.findWithLinesByReservationId(id).orElseThrow();
                release(r, ReleaseReason.EXPIRED, SagaContext.none(null));
                events.released(SagaContext.none(null), r.getOrderId(), r, ReleaseReason.EXPIRED.name());
                log.info("Reservation {} of order {} expired (was due {})", id, r.getOrderId(), r.getExpiresAt());
            }
            return ids.size();
        });
    }

    public Optional<Reservation> find(UUID reservationId) {
        return reservations.findWithLinesByReservationId(reservationId);
    }

    public List<Reservation> findByOrder(UUID orderId) {
        return reservations.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    private void release(Reservation r, ReleaseReason reason, SagaContext ctx) {
        Instant now = Instant.now(clock);
        r.release(reason, now);
        // lock in a fixed order (sku, location), like reserve does, so two releases cannot deadlock
        List<ReservationLine> lines = r.getLines().stream()
                .sorted(Comparator.comparing(ReservationLine::getSku).thenComparing(ReservationLine::getLocationId))
                .toList();
        List<Stock> touched = new ArrayList<>();
        for (ReservationLine line : lines) {
            Stock row = stock.lockRow(line.getSku(), line.getLocationId())
                    .orElseThrow(() -> new IllegalStateException("No stock row " + line.getSku() + "@" + line.getLocationId()));
            row.release(line.getQuantity(), now);
            touched.add(row);
        }
        em.flush();       // optimistic check of the reservation: a concurrent release fails here and is retried
        touched.forEach(s -> events.changed(s, "RELEASED", ctx.correlationId()));
        meters.counter("oms.inventory.releases", "reason", reason.name()).increment();
        log.info("Order {}: released reservation {} ({})", r.getOrderId(), r.getReservationId(), reason);
    }

    private void setLockTimeout() {
        em.createNativeQuery("SET LOCAL lock_timeout = '" + props.lockTimeout().toMillis() + "ms'").executeUpdate();
    }

    private static String describe(Reservation r) {
        return r.getLines().stream().map(l -> l.getQuantity() + " x " + l.getSku() + "@" + l.getLocationId())
                .collect(Collectors.joining(", "));
    }
}
