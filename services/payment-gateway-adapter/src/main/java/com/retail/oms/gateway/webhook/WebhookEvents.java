package com.retail.oms.gateway.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.retail.oms.events.payment.GatewayWebhookEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

/** Gateway webhook JSON -> one GatewayWebhookEvent shape for payment-service. */
public final class WebhookEvents {

    static final int MAX_PAYLOAD = 16 * 1024;

    /** Stripe: {id: evt_..., type, data: {object: {id, object, status, payment_intent?, amount}}}. */
    public static GatewayWebhookEvent stripe(JsonNode event, byte[] raw, Clock clock) {
        String id = required(event, "id");
        JsonNode object = event.path("data").path("object");
        String objectType = object.path("object").asText("");
        String paymentRef = "payment_intent".equals(objectType) ? text(object, "id") : text(object, "payment_intent");
        return GatewayWebhookEvent.newBuilder().setEventId(id).setReceivedAt(Instant.now(clock)).setGateway("STRIPE")
                .setType(required(event, "type")).setObjectId(text(object, "id")).setObjectStatus(text(object, "status"))
                .setPaymentRef(paymentRef)
                .setAmountMinor(object.path("amount").isNumber() ? object.path("amount").asLong() : null)
                .setPayload(payload(raw)).build();
    }

    /** PayPal: {id: WH-..., event_type, resource: {id, status, amount: {value}}}. */
    public static GatewayWebhookEvent paypal(JsonNode event, byte[] raw, Clock clock) {
        String id = required(event, "id");
        JsonNode resource = event.path("resource");
        Long amount = null;
        if (resource.path("amount").path("value").isTextual()) {
            amount = new BigDecimal(resource.path("amount").path("value").asText()).movePointRight(2).longValueExact();
        }
        return GatewayWebhookEvent.newBuilder().setEventId(id).setReceivedAt(Instant.now(clock)).setGateway("PAYPAL")
                .setType(required(event, "event_type")).setObjectId(text(resource, "id"))
                .setObjectStatus(text(resource, "status")).setPaymentRef(null).setAmountMinor(amount)
                .setPayload(payload(raw)).build();
    }

    private static String required(JsonNode node, String field) {
        String v = text(node, field);
        if (v == null || v.isBlank()) {
            throw new WebhookRejectedException("Webhook without " + field);
        }
        return v;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.isContainerNode() ? null : v.asText();
    }

    private static String payload(byte[] raw) {
        String s = new String(raw, StandardCharsets.UTF_8);
        return s.length() <= MAX_PAYLOAD ? s : s.substring(0, MAX_PAYLOAD);
    }

    private WebhookEvents() {
    }
}
