package com.retail.oms.order.infrastructure.messaging;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One row per domain event, inserted in the same transaction as the order change.
 * The application never reads it back: Debezium picks new rows up from the PostgreSQL WAL.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class OutboxEvent {

    @Id
    private UUID id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    /** Avro in Confluent wire format; see OutboxWriter. */
    private byte[] payload;
    private String correlationId;
    private Instant createdAt;
}
