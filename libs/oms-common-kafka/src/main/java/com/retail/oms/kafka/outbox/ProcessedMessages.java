package com.retail.oms.kafka.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumer for services with a SQL database: the message id is inserted into {@code processed_message}
 * in the SAME transaction as the change the message causes. A re-delivered message hits the primary key and is
 * skipped; a crash rolls back both, so the re-delivery is processed as if for the first time.
 * (order-query-service has no SQL database and uses Redis + idempotent Elasticsearch writes instead.)
 * <pre>processed_message(consumer, message_id, processed_at, PRIMARY KEY (consumer, message_id))</pre>
 */
public class ProcessedMessages {

    private final JdbcTemplate jdbc;

    public ProcessedMessages(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this is the first time; false if the message was already handled (skip it) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, String messageId) {
        return jdbc.update("""
                INSERT INTO processed_message (consumer, message_id, processed_at) VALUES (?, ?, now())
                ON CONFLICT (consumer, message_id) DO NOTHING""", consumer, messageId) == 1;
    }
}
