package com.retail.oms.orchestrator.infrastructure.messaging;

import com.retail.oms.kafka.outbox.OutboxRelay;
import com.retail.oms.orchestrator.application.engine.SagaEngine;
import com.retail.oms.orchestrator.config.OutboxProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background work: drain the outbox (the commands), time out sagas that wait too long, clean old outbox rows. */
@Component
@Slf4j
public class ScheduledJobs {

    static final int TIMEOUT_BATCH = 20;

    private final OutboxRelay relay;
    private final SagaEngine engine;
    private final OutboxProperties outbox;

    public ScheduledJobs(OutboxRelay relay, SagaEngine engine, OutboxProperties outbox) {
        this.relay = relay;
        this.engine = engine;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${oms.outbox.poll-interval}")
    public void drainOutbox() {
        try {
            while (relay.publishBatch() == outbox.batchSize()) {
                // a full batch: there is probably more
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed, next run in {}: {}", outbox.pollInterval(), e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${oms.saga.timeout-check-interval}")
    public void timeOutSagas() {
        try {
            while (engine.timeOutOverdue(TIMEOUT_BATCH) == TIMEOUT_BATCH) {
                // more overdue sagas than one batch
            }
        } catch (RuntimeException e) {
            // e.g. order-service down while cancelling: the saga stays overdue and is tried again next run
            log.warn("Saga timeout run failed, will try again: {}", e.toString());
        }
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void cleanOutbox() {
        log.info("Outbox cleanup: {} sent rows older than {} deleted", relay.deletePublishedBefore(outbox.retention()),
                outbox.retention());
    }
}
