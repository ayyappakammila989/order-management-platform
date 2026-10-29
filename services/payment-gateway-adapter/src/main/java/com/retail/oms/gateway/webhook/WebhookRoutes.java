package com.retail.oms.gateway.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.events.EventHeaders;
import com.retail.oms.events.Topics;
import com.retail.oms.events.payment.GatewayWebhookEvent;
import com.retail.oms.gateway.application.AvroBytes;
import com.retail.oms.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Architecture 5, route gateway-webhook-in: verify the signature, de-duplicate by the gateway's event id
 * (Redis SET NX evt:webhook:{gateway}:{eventId}, 3 days), publish to oms.payment.gateway-event.
 * If Kafka does not take the event, the key is removed again and the caller gets an error, so the gateway
 * retries; payment-service de-duplicates a second time (webhook_event table), in case Redis lost a key.
 */
@Component
public class WebhookRoutes extends RouteBuilder {

    public static final String STRIPE = "direct:webhook-stripe";
    public static final String PAYPAL = "direct:webhook-paypal";
    public static final String DUPLICATE = "omsWebhookDuplicate";
    public static final String RAW = "omsWebhookRaw";
    public static final String HEADERS = "omsWebhookHeaders";
    static final String EVENT = "omsWebhookEvent";
    static final String DEDUPE_KEY = "omsWebhookKey";

    private final StripeSignature stripeSignature;
    private final PayPalWebhookVerifier paypalVerifier;
    private final StringRedisTemplate redis;
    private final AvroBytes avro;
    private final ObjectMapper json;
    private final GatewayProperties props;
    private final MeterRegistry meters;
    private final Clock clock;

    public WebhookRoutes(PayPalWebhookVerifier paypalVerifier, StringRedisTemplate redis, AvroBytes avro,
            ObjectMapper json, GatewayProperties props, MeterRegistry meters, Clock clock) {
        this.stripeSignature = new StripeSignature(props.stripe().webhookSecret(), props.stripe().signatureTolerance(), clock);
        this.paypalVerifier = paypalVerifier;
        this.redis = redis;
        this.avro = avro;
        this.json = json;
        this.props = props;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public void configure() {
        from(STRIPE).routeId("stripe-webhook-in")
                .process(e -> {
                    byte[] raw = e.getProperty(RAW, byte[].class);
                    stripeSignature.verify(headers(e).get("stripe-signature"), raw);      // before parsing
                    e.setProperty(EVENT, WebhookEvents.stripe(parse(raw), raw, clock));
                })
                .to("direct:webhook-publish");

        from(PAYPAL).routeId("paypal-webhook-in")
                .process(e -> {
                    byte[] raw = e.getProperty(RAW, byte[].class);
                    JsonNode event = parse(raw);
                    paypalVerifier.verify(headers(e), event);
                    e.setProperty(EVENT, WebhookEvents.paypal(event, raw, clock));
                })
                .to("direct:webhook-publish");

        from("direct:webhook-publish").routeId("gateway-webhook-in")
                .process(this::deduplicate)
                .filter(exchangeProperty(DUPLICATE).isEqualTo(false))
                    .doTry()
                        .process(this::toKafkaMessage)
                        .to("kafka:" + Topics.PAYMENT_GATEWAY_EVENT
                                + "?valueSerializer=org.apache.kafka.common.serialization.ByteArraySerializer")
                        .process(e -> count(e, "accepted"))
                    .doCatch(Exception.class)
                        .process(e -> {
                            redis.delete(e.getProperty(DEDUPE_KEY, String.class));    // let the gateway's retry through
                            throw e.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                        })
                    .end()
                .end();
    }

    private void deduplicate(Exchange e) {
        GatewayWebhookEvent event = e.getProperty(EVENT, GatewayWebhookEvent.class);
        String key = "evt:webhook:" + event.getGateway().toLowerCase(java.util.Locale.ROOT) + ":" + event.getEventId();
        boolean first = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, event.getType(), props.webhooks().dedupeTtl()));
        e.setProperty(DEDUPE_KEY, key);
        e.setProperty(DUPLICATE, !first);
        if (!first) {
            log.info("{} webhook {} ({}) seen before, not published again", event.getGateway(), event.getEventId(), event.getType());
            count(e, "duplicate");
        }
    }

    private void toKafkaMessage(Exchange e) {
        GatewayWebhookEvent event = e.getProperty(EVENT, GatewayWebhookEvent.class);
        e.getMessage().removeHeaders("*");
        e.getMessage().setHeader(KafkaConstants.KEY, event.getEventId());
        e.getMessage().setHeader(EventHeaders.EVENT_ID, event.getEventId());
        e.getMessage().setHeader(EventHeaders.EVENT_TYPE, "payment.gateway-event");
        e.getMessage().setBody(avro.serialize(Topics.PAYMENT_GATEWAY_EVENT, event));
        log.info("{} webhook {} ({}) for {} published", event.getGateway(), event.getEventId(), event.getType(),
                event.getObjectId());
    }

    private void count(Exchange e, String result) {
        GatewayWebhookEvent event = e.getProperty(EVENT, GatewayWebhookEvent.class);
        meters.counter("oms.gateway.webhooks", "gateway", event.getGateway(), "result", result).increment();
    }

    private JsonNode parse(byte[] raw) {
        try {
            JsonNode node = json.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new WebhookRejectedException("Webhook body is not a JSON object");
            }
            return node;
        } catch (java.io.IOException ex) {
            throw new WebhookRejectedException("Webhook body is not JSON");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> headers(Exchange e) {
        return e.getProperty(HEADERS, Map.class);
    }
}
