package com.retail.oms.events;

/**
 * Kafka record headers set on every event. With the outbox, Debezium's EventRouter copies them
 * from outbox_event columns (see infra/debezium/order-outbox-connector.json).
 */
public final class EventHeaders {

    /** Unique event id (outbox row id). Consumers store it to ignore duplicates (evt:{consumer}:{eventId}). */
    public static final String EVENT_ID = "id";
    /** e.g. order.created */
    public static final String EVENT_TYPE = "eventType";
    public static final String AGGREGATE_TYPE = "aggregateType";
    /** Same value as the X-Correlation-Id HTTP header of the request that caused the event. */
    public static final String CORRELATION_ID = "correlationId";

    private EventHeaders() {
    }
}
