package com.retail.oms.kafka.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polling publisher for the outbox: takes the oldest unsent rows with {@code FOR UPDATE SKIP LOCKED}, sends them to
 * Kafka (waiting for the broker's ack) and marks them sent, all in one short transaction.
 * <ul>
 *   <li>Several instances can run at once: SKIP LOCKED gives each a different set of rows, nobody waits.</li>
 *   <li>At-least-once: a crash after the send but before the commit sends the row again, so consumers de-duplicate
 *       on the {@code id} header.</li>
 *   <li>If a send fails, the batch stops there (rows after it stay unsent), so one instance keeps the order of rows.</li>
 * </ul>
 * order-service uses Debezium (log tailing) instead; this is the other common way to drain an outbox.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, byte[]> kafka;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Clock clock;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, byte[]> kafka, int batchSize,
            Duration sendTimeout, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.clock = clock;
        this.published = meters.counter("oms.outbox.published");
        this.failures = meters.counter("oms.outbox.send_failures");
    }

    private record Row(UUID id, String topic, String key, String type, byte[] payload, String correlationId) {
    }

    /** Sends one batch. @return number of rows sent */
    public int publishBatch() {
        Integer sent = tx.execute(status -> {
            List<Row> rows = jdbc.query("""
                    SELECT id, topic, message_key, event_type, payload, correlation_id FROM outbox_event
                    WHERE published_at IS NULL ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED""",
                    (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"), rs.getString("message_key"),
                            rs.getString("event_type"), rs.getBytes("payload"), rs.getString("correlation_id")),
                    batchSize);
            List<Object[]> done = new ArrayList<>();
            Timestamp now = Timestamp.from(Instant.now(clock));
            for (Row row : rows) {
                try {
                    var headers = new RecordHeaders();
                    headers.add("id", row.id().toString().getBytes(StandardCharsets.UTF_8));
                    headers.add("eventType", row.type().getBytes(StandardCharsets.UTF_8));
                    if (row.correlationId() != null) {
                        headers.add("correlationId", row.correlationId().getBytes(StandardCharsets.UTF_8));
                    }
                    kafka.send(new ProducerRecord<>(row.topic(), null, row.key(), row.payload(), headers))
                            .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    done.add(new Object[] {now, row.id()});
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    failures.increment();
                    String error = (e.getCause() != null ? e.getCause() : e).toString();
                    log.warn("Outbox: sending {} to {} failed, will retry: {}", row.id(), row.topic(), error);
                    jdbc.update("UPDATE outbox_event SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                            error.length() > 500 ? error.substring(0, 500) : error, row.id());
                    break;             // keep the order: the rows after this one wait for the next run
                }
            }
            if (!done.isEmpty()) {
                jdbc.batchUpdate("UPDATE outbox_event SET published_at = ? WHERE id = ?", done);
            }
            return done.size();
        });
        int n = sent == null ? 0 : sent;
        published.increment(n);
        return n;
    }

    /** Deletes rows that were sent more than {@code retention} ago. @return rows deleted */
    public int deletePublishedBefore(Duration retention) {
        return jdbc.update("DELETE FROM outbox_event WHERE published_at < ?",
                Timestamp.from(Instant.now(clock).minus(retention)));
    }
}
