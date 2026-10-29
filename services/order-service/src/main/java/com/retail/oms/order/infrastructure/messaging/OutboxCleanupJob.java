package com.retail.oms.order.infrastructure.messaging;

import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Debezium reads the outbox from the WAL, so rows are not needed after commit. They are kept for a few days
 * to help debugging, then deleted. The publication only covers INSERT, so these deletes produce no events.
 */
@Slf4j
@Component
public class OutboxCleanupJob {

    private final OutboxEventRepository outbox;
    private final Clock clock;
    private final Duration retention;

    public OutboxCleanupJob(OutboxEventRepository outbox, Clock clock,
            @Value("${oms.order.outbox-retention}") Duration retention) {
        this.outbox = outbox;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(cron = "${oms.order.outbox-cleanup-cron}")
    @Transactional
    public int purge() {
        int deleted = outbox.deleteOlderThan(clock.instant().minus(retention));
        log.info("Outbox cleanup removed {} rows older than {}", deleted, retention);
        return deleted;
    }
}
