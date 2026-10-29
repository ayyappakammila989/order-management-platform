package com.retail.oms.inventory.infrastructure.messaging;

import com.retail.oms.inventory.application.ReservationService;
import com.retail.oms.inventory.config.InventoryProperties;
import com.retail.oms.inventory.config.OutboxProperties;
import com.retail.oms.kafka.outbox.OutboxRelay;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background work: drain the outbox, expire reservations, clean old outbox rows. */
@Component
@Slf4j
public class ScheduledJobs {

    private final OutboxRelay relay;
    private final ReservationService reservations;
    private final InventoryProperties inventory;
    private final OutboxProperties outbox;

    public ScheduledJobs(OutboxRelay relay, ReservationService reservations, InventoryProperties inventory,
            OutboxProperties outbox) {
        this.relay = relay;
        this.reservations = reservations;
        this.inventory = inventory;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${oms.outbox.poll-interval}")
    public void drainOutbox() {
        try {
            while (relay.publishBatch() == outbox.batchSize()) {
                // a full batch: there is probably more, go on without waiting
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed, next run in {}: {}", outbox.pollInterval(), e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${oms.inventory.expiry-interval}", initialDelayString = "${oms.inventory.expiry-interval}")
    public void expireReservations() {
        try {
            while (reservations.expireBatch() == inventory.expiryBatchSize()) {
                // more expired reservations than one batch
            }
        } catch (RuntimeException e) {
            log.warn("Reservation expiry run failed: {}", e.toString());
        }
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void cleanOutbox() {
        log.info("Outbox cleanup: {} sent rows older than {} deleted", relay.deletePublishedBefore(outbox.retention()),
                outbox.retention());
    }
}
