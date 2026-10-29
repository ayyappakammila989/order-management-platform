package com.retail.oms.fraud.infrastructure.messaging;

import com.retail.oms.fraud.config.OutboxProperties;
import com.retail.oms.kafka.outbox.OutboxRelay;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background work: drain the outbox, clean old outbox rows. */
@Component
@Slf4j
public class ScheduledJobs {

    private final OutboxRelay relay;
    private final OutboxProperties outbox;

    public ScheduledJobs(OutboxRelay relay, OutboxProperties outbox) {
        this.relay = relay;
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

    @Scheduled(cron = "0 20 3 * * *")
    public void cleanOutbox() {
        log.info("Outbox cleanup: {} sent rows older than {} deleted", relay.deletePublishedBefore(outbox.retention()),
                outbox.retention());
    }
}
