package com.retail.oms.events;

/** Kafka topic names: oms.&lt;domain&gt;.&lt;event-in-past-tense&gt; (architecture 14.5). */
public final class Topics {

    public static final String ORDER_CREATED = "oms.order.created";
    public static final String ORDER_CONFIRMED = "oms.order.confirmed";
    public static final String ORDER_CANCELLED = "oms.order.cancelled";

    // inventory saga: commands end in -cmd (architecture 14.5), replies and facts are past tense
    public static final String INVENTORY_RESERVE_CMD = "oms.inventory.reserve-cmd";
    public static final String INVENTORY_RELEASE_CMD = "oms.inventory.release-cmd";
    public static final String INVENTORY_RESERVED = "oms.inventory.reserved";
    public static final String INVENTORY_RESERVE_FAILED = "oms.inventory.reserve-failed";
    public static final String INVENTORY_RELEASED = "oms.inventory.released";
    public static final String INVENTORY_CHANGED = "oms.inventory.changed";
    /** Absolute stock counts per location (SFTP files / store POS; a Kafka test producer for now). */
    public static final String INVENTORY_SNAPSHOT = "oms.inventory.snapshot";

    /** Status events go to oms.order.&lt;status&gt;, e.g. CONFIRMED -> oms.order.confirmed. */
    public static String orderStatus(String status) {
        return "oms.order." + status.toLowerCase(java.util.Locale.ROOT);
    }

    private Topics() {
    }
}
