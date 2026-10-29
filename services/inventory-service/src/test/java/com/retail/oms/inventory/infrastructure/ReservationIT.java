package com.retail.oms.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retail.oms.events.inventory.StockLevel;
import com.retail.oms.events.inventory.StockSnapshotEvent;
import com.retail.oms.inventory.application.AvailabilityService;
import com.retail.oms.inventory.application.InvalidMessageException;
import com.retail.oms.inventory.application.ReservationService;
import com.retail.oms.inventory.application.ReservationService.ReserveOutcome;
import com.retail.oms.inventory.application.ReserveRequest;
import com.retail.oms.inventory.application.SagaContext;
import com.retail.oms.inventory.application.Shortage;
import com.retail.oms.inventory.application.SnapshotService;
import com.retail.oms.inventory.domain.ReleaseReason;
import com.retail.oms.inventory.domain.Reservation;
import com.retail.oms.inventory.domain.ReservationStatus;
import com.retail.oms.inventory.support.AbstractInventoryIT;
import com.retail.oms.inventory.support.Infrastructure;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reservations against real PostgreSQL: all-or-nothing, no oversell under concurrency, FOR UPDATE SKIP LOCKED,
 * the bounded second pass, optimistic locking (snapshot vs reservation, release vs expiry), expiry, the cache.
 */
class ReservationIT extends AbstractInventoryIT {

    @Autowired ReservationService reservations;
    @Autowired SnapshotService snapshots;
    @Autowired AvailabilityService availability;
    @Autowired TransactionTemplate tx;
    @Autowired MeterRegistry meters;
    @Autowired StringRedisTemplate redis;

    final ExecutorService pool = Executors.newFixedThreadPool(32);

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    ReserveOutcome reserve(UUID orderId, String zip, String sku, int quantity) {
        return reservations.reserve(new ReserveRequest(orderId, zip, List.of(new ReserveRequest.Line(sku, quantity))),
                SagaContext.none(null));
    }

    Reservation reserved(ReserveOutcome outcome) {
        assertThat(outcome).isInstanceOf(ReserveOutcome.Reserved.class);
        return ((ReserveOutcome.Reserved) outcome).reservation();
    }

    double count(String name, String... tags) {
        var c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    /** Another transaction locks one stock row and keeps it for {@code hold}, like a concurrent order would. */
    Future<?> holdRowLock(String sku, String location, Duration hold, CountDownLatch locked) {
        return pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT * FROM stock WHERE sku = ? AND location_id = ? FOR UPDATE", sku, location);
            locked.countDown();
            sleep(hold);
        }));
    }

    static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void reservesFromTheNearestLocationsAndSplitsWhenNeeded() {
        String sku = newSku("STORE-NYC", 3, "DC-EAST", 10, "DC-WEST", 10);

        Reservation r = reserved(reserve(UUID.randomUUID(), "10001", sku, 5));

        assertThat(r.getLines()).extracting(l -> l.getLocationId() + ":" + l.getQuantity())
                .containsExactlyInAnyOrder("STORE-NYC:3", "DC-EAST:2");
        assertThat(reserved(sku, "STORE-NYC")).isEqualTo(3);
        assertThat(reserved(sku, "DC-EAST")).isEqualTo(2);
        assertThat(reserved(sku, "DC-WEST")).isZero();
        assertThat(r.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(29)));   // reservation-ttl 30m
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE topic = 'oms.inventory.changed' AND message_key = ?",
                Integer.class, sku)).isEqualTo(2);                       // one fact per touched row; no saga reply (REST)
    }

    @Test
    void allOrNothingWhenOneLineIsShort() {
        String plenty = newSku("DC-EAST", 10);
        String scarce = newSku("DC-EAST", 2);
        UUID order = UUID.randomUUID();

        var outcome = reservations.reserve(new ReserveRequest(order, null, List.of(
                new ReserveRequest.Line(plenty, 1), new ReserveRequest.Line(scarce, 3))), SagaContext.none(null));

        assertThat(outcome).isEqualTo(new ReserveOutcome.Rejected(List.of(new Shortage(scarce, 3, 2))));
        assertThat(totalReserved(plenty)).isZero();
        assertThat(reservations.findByOrder(order)).isEmpty();
    }

    @Test
    void thirtyConcurrentOrdersForTenUnitsNeverOversell() throws Exception {
        String sku = newSku("DC-EAST", 6, "DC-WEST", 4);
        var start = new CountDownLatch(1);
        List<Future<ReserveOutcome>> results = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            results.add(pool.submit((Callable<ReserveOutcome>) () -> {
                start.await();
                return reserve(UUID.randomUUID(), null, sku, 1);
            }));
        }
        start.countDown();

        int ok = 0;
        int rejected = 0;
        for (var f : results) {
            var outcome = f.get(30, TimeUnit.SECONDS);
            if (outcome instanceof ReserveOutcome.Reserved) {
                ok++;
            } else if (outcome instanceof ReserveOutcome.Rejected) {
                rejected++;
            }
        }
        assertThat(ok).isEqualTo(10);
        assertThat(rejected).isEqualTo(20);
        assertThat(reserved(sku, "DC-EAST")).isEqualTo(6);
        assertThat(reserved(sku, "DC-WEST")).isEqualTo(4);
    }

    @Test
    void skipLockedTakesAnotherLocationInsteadOfWaiting() throws Exception {
        String sku = newSku("DC-EAST", 5, "DC-WEST", 5);
        var locked = new CountDownLatch(1);
        Future<?> holder = holdRowLock(sku, "DC-EAST", Duration.ofSeconds(3), locked);
        locked.await();

        long start = System.nanoTime();
        Reservation r = reserved(reserve(UUID.randomUUID(), "08817", sku, 1));    // DC-EAST would be the first choice
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(r.getLines()).extracting(l -> l.getLocationId()).containsExactly("DC-WEST");
        assertThat(took).isLessThan(Duration.ofSeconds(1));                       // did not wait for the 3 s lock
        holder.get();
    }

    @Test
    void secondPassWaitsForTheSkippedRowWhenNothingElseIsFree() throws Exception {
        String sku = newSku("DC-EAST", 5);
        double secondPass = count("oms.inventory.reserve_second_pass");
        var locked = new CountDownLatch(1);
        Future<?> holder = holdRowLock(sku, "DC-EAST", Duration.ofMillis(700), locked);
        locked.await();

        long start = System.nanoTime();
        Reservation r = reserved(reserve(UUID.randomUUID(), null, sku, 2));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(r.getLines()).extracting(l -> l.getLocationId()).containsExactly("DC-EAST");
        assertThat(took).isGreaterThan(Duration.ofMillis(400));                   // waited for the other transaction
        assertThat(count("oms.inventory.reserve_second_pass") - secondPass).isEqualTo(1);
        holder.get();
    }

    @Test
    void secondPassGivesUpAfterTheLockTimeout() throws Exception {
        String sku = newSku("DC-EAST", 5);
        var locked = new CountDownLatch(1);
        Future<?> holder = holdRowLock(sku, "DC-EAST", Duration.ofSeconds(4), locked);
        locked.await();

        long start = System.nanoTime();
        assertThatThrownBy(() -> reserve(UUID.randomUUID(), null, sku, 1))
                .isInstanceOf(PessimisticLockingFailureException.class);          // lock_timeout 1.5 s in the tests
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(1400), Duration.ofSeconds(3));
        assertThat(totalReserved(sku)).isZero();
        holder.get();
    }

    @Test
    void snapshotThatRacesAReservationIsRetriedWithoutLosingTheReservation() throws Exception {
        String sku = newSku("DC-EAST", 10);
        double retries = count("oms.inventory.optimistic_retries");
        var updated = new CountDownLatch(1);
        // a reservation-like change that is not committed yet: reserved + 1, version + 1, row locked for 600 ms
        Future<?> writer = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE stock SET reserved = reserved + 1, version = version + 1 WHERE sku = ? AND location_id = 'DC-EAST'", sku);
            updated.countDown();
            sleep(Duration.ofMillis(600));
        }));
        updated.await();

        var snapshot = StockSnapshotEvent.newBuilder().setSnapshotId(UUID.randomUUID().toString())
                .setTakenAt(Instant.now()).setLocationId("DC-EAST").setSource("SFTP")
                .setItems(List.of(new StockLevel(sku, 7))).build();
        long start = System.nanoTime();
        assertThat(snapshots.apply(snapshot, null)).isEqualTo(SnapshotService.Outcome.APPLIED);
        writer.get();

        // the snapshot read version 0, its UPDATE ... WHERE version = 0 waited for the lock, found version 1: retried
        assertThat(count("oms.inventory.optimistic_retries") - retries).isGreaterThanOrEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(Duration.ofMillis(400));
        assertThat(onHand(sku, "DC-EAST")).isEqualTo(7);
        assertThat(reserved(sku, "DC-EAST")).isEqualTo(1);                        // the other change is not lost
    }

    @Test
    void releaseAndExpiryRacingForTheSameReservationReleaseItOnce() throws Exception {
        String sku = newSku("DC-EAST", 100);
        double retries = count("oms.inventory.optimistic_retries");
        for (int round = 0; round < 10; round++) {
            reserved(reserve(UUID.randomUUID(), null, sku, 3));                  // stays: shows a double release
            Reservation r = reserved(reserve(UUID.randomUUID(), null, sku, 2));
            jdbc.update("UPDATE reservation SET expires_at = now() - interval '1 second' WHERE reservation_id = ?",
                    r.getReservationId());
            int before = reserved(sku, "DC-EAST");
            var go = new CountDownLatch(1);
            Future<?> manual = pool.submit(() -> {
                go.await();
                return reservations.releaseById(r.getReservationId(), ReleaseReason.MANUAL, null);
            });
            Future<?> expiry = pool.submit(() -> {
                go.await();
                return reservations.expireBatch();
            });
            go.countDown();
            manual.get(10, TimeUnit.SECONDS);
            expiry.get(10, TimeUnit.SECONDS);

            assertThat(reserved(sku, "DC-EAST")).as("round %d", round).isEqualTo(before - 2);
            assertThat(reservations.find(r.getReservationId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.RELEASED);
        }
        // how often the two really collided depends on timing; the result above must be right either way
        System.out.printf("release vs expiry: %.0f optimistic retries in 10 rounds%n",
                count("oms.inventory.optimistic_retries") - retries);
    }

    @Test
    void expiredReservationsAreReleasedButConfirmedOrdersKeepTheirStock() {
        String sku = newSku("DC-EAST", 10);
        UUID abandoned = UUID.randomUUID();
        UUID confirmed = UUID.randomUUID();
        Reservation a = reserved(reserve(abandoned, null, sku, 2));
        reserved(reserve(confirmed, null, sku, 3));
        reservations.keepUntilShipment(confirmed, UUID.randomUUID().toString());
        jdbc.update("UPDATE reservation SET expires_at = now() - interval '1 minute' WHERE reservation_id = ?", a.getReservationId());

        assertThat(reservations.expireBatch()).isGreaterThanOrEqualTo(1);

        var after = reservations.find(a.getReservationId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(after.getReleaseReason()).isEqualTo(ReleaseReason.EXPIRED);
        assertThat(reservations.findByOrder(confirmed).get(0).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservations.findByOrder(confirmed).get(0).getExpiresAt()).isNull();
        assertThat(totalReserved(sku)).isEqualTo(3);
    }

    @Test
    void secondReserveForTheSameOrderReturnsTheSameReservation() {
        String sku = newSku("DC-EAST", 10);
        UUID order = UUID.randomUUID();
        Reservation first = reserved(reserve(order, null, sku, 4));

        var again = (ReserveOutcome.Reserved) reserve(order, null, sku, 4);

        assertThat(again.existing()).isTrue();
        assertThat(again.reservation().getReservationId()).isEqualTo(first.getReservationId());
        assertThat(totalReserved(sku)).isEqualTo(4);
    }

    @Test
    void snapshotsOlderThanTheLastOneAndForUnknownLocationsAreNotApplied() {
        String sku = newSku("STORE-SF", 10);
        Instant t = Instant.now();
        var newer = StockSnapshotEvent.newBuilder().setSnapshotId(UUID.randomUUID().toString()).setTakenAt(t)
                .setLocationId("STORE-SF").setSource("POS").setItems(List.of(new StockLevel(sku, 8))).build();
        var older = StockSnapshotEvent.newBuilder(newer).setSnapshotId(UUID.randomUUID().toString())
                .setTakenAt(t.minusSeconds(60)).setItems(List.of(new StockLevel(sku, 99))).build();

        assertThat(snapshots.apply(newer, null)).isEqualTo(SnapshotService.Outcome.APPLIED);
        assertThat(snapshots.apply(older, null)).isEqualTo(SnapshotService.Outcome.STALE);
        assertThat(snapshots.apply(newer, null)).isEqualTo(SnapshotService.Outcome.DUPLICATE);
        assertThat(onHand(sku, "STORE-SF")).isEqualTo(8);

        var unknown = StockSnapshotEvent.newBuilder(newer).setSnapshotId(UUID.randomUUID().toString())
                .setLocationId("NOWHERE").build();
        assertThatThrownBy(() -> snapshots.apply(unknown, null)).isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void availabilityIsCachedAndDroppedWhenStockChanges() {
        String sku = newSku("STORE-NYC", 4, "DC-WEST", 6);

        var a = availability.availability(sku, "10001");
        assertThat(a.available()).isEqualTo(10);
        assertThat(a.locations()).extracting(l -> l.locationId()).containsExactly("STORE-NYC", "DC-WEST");
        String key = "atp:" + sku + ":10001";
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.getExpire(key)).isBetween(1L, 30L);                       // architecture 8: 30 s

        reserved(reserve(UUID.randomUUID(), "10001", sku, 3));

        assertThat(redis.hasKey(key)).isFalse();                                   // evicted after commit
        assertThat(availability.availability(sku, "10001").available()).isEqualTo(7);
    }

    @Test
    void availabilityStillAnswersWhenRedisIsDown() {
        String sku = newSku("DC-EAST", 5);
        var r = Infrastructure.REDIS;
        r.getDockerClient().pauseContainerCmd(r.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            assertThat(availability.availability(sku, null).available()).isEqualTo(5);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));   // 500 ms timeouts
        } finally {
            r.getDockerClient().unpauseContainerCmd(r.getContainerId()).exec();
        }
    }
}
