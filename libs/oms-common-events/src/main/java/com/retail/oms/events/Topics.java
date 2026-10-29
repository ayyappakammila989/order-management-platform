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

    // fraud saga step (architecture 3.2 step 2): one decision schema, the topic says which decision
    public static final String FRAUD_CHECK_CMD = "oms.fraud.check-cmd";
    public static final String FRAUD_CANCEL_CMD = "oms.fraud.cancel-cmd";
    public static final String FRAUD_APPROVED = "oms.fraud.approved";
    public static final String FRAUD_REVIEW = "oms.fraud.review";
    public static final String FRAUD_REJECTED = "oms.fraud.rejected";
    public static final String FRAUD_CANCELLED = "oms.fraud.cancelled";

    // payment saga step (architecture 3.2 step 3) and payment facts
    public static final String PAYMENT_AUTHORIZE_CMD = "oms.payment.authorize-cmd";
    public static final String PAYMENT_VOID_CMD = "oms.payment.void-cmd";
    public static final String PAYMENT_AUTHORIZED = "oms.payment.authorized";
    public static final String PAYMENT_FAILED = "oms.payment.failed";
    /** the gateway did not answer; payment-service keeps trying with the same key and the saga waits */
    public static final String PAYMENT_PENDING = "oms.payment.pending";
    public static final String PAYMENT_VOIDED = "oms.payment.voided";
    public static final String PAYMENT_CAPTURED = "oms.payment.captured";
    public static final String PAYMENT_REFUNDED = "oms.payment.refunded";

    // payment-service <-> payment-gateway-adapter (architecture 5: Camel routes)
    public static final String PAYMENT_GATEWAY_CMD = "oms.payment.gateway-cmd";
    public static final String PAYMENT_GATEWAY_RESULT = "oms.payment.gateway-result";
    public static final String PAYMENT_GATEWAY_EVENT = "oms.payment.gateway-event";

    public static final String SHIPMENT_SHIPPED = "oms.shipment.shipped";

    /** FraudDecisionEvent topic for APPROVED / REVIEW / REJECTED. */
    public static String fraudDecision(String decision) {
        return "oms.fraud." + decision.toLowerCase(java.util.Locale.ROOT);
    }

    /** Status events go to oms.order.&lt;status&gt;, e.g. CONFIRMED -> oms.order.confirmed. */
    public static String orderStatus(String status) {
        return "oms.order." + status.toLowerCase(java.util.Locale.ROOT);
    }

    private Topics() {
    }
}
