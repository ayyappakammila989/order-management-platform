package com.retail.oms.events;

/** Kafka topic names: oms.&lt;domain&gt;.&lt;event-in-past-tense&gt; (architecture 14.5). */
public final class Topics {

    public static final String ORDER_CREATED = "oms.order.created";
    public static final String ORDER_CONFIRMED = "oms.order.confirmed";
    public static final String ORDER_CANCELLED = "oms.order.cancelled";

    /** Status events go to oms.order.&lt;status&gt;, e.g. CONFIRMED -> oms.order.confirmed. */
    public static String orderStatus(String status) {
        return "oms.order." + status.toLowerCase(java.util.Locale.ROOT);
    }

    private Topics() {
    }
}
