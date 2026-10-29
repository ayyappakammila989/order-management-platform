package com.retail.oms.inventory.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a new transaction and, if another transaction changed the same row first (optimistic lock: the
 * UPDATE ... WHERE version = ? found nothing), runs it again from a fresh read. Metric oms.inventory.optimistic_retries.
 */
@Component
@Slf4j
public class OptimisticRetry {

    static final int MAX_ATTEMPTS = 5;

    private final TransactionTemplate tx;
    private final Counter retries;

    public OptimisticRetry(TransactionTemplate tx, MeterRegistry meters) {
        this.tx = tx;
        this.retries = meters.counter("oms.inventory.optimistic_retries");
    }

    public <T> T inTransaction(String what, Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (!isOptimisticLockFailure(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                retries.increment();
                log.info("{}: a concurrent change won (attempt {}), retrying: {}", what, attempt, e.getMessage());
            }
        }
    }

    /**
     * Spring's exception at commit, or the raw JPA/Hibernate one when the work itself calls flush() (only
     * repository proxies translate exceptions).
     */
    static boolean isOptimisticLockFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof OptimisticLockingFailureException || t instanceof jakarta.persistence.OptimisticLockException
                    || t instanceof org.hibernate.StaleStateException) {
                return true;
            }
        }
        return false;
    }
}
