package com.retail.oms.gateway.routes;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.retail.oms.events.Topics;
import com.retail.oms.events.payment.GatewayCommand;
import com.retail.oms.events.payment.GatewayResult;
import com.retail.oms.events.payment.GatewayWebhookEvent;
import com.retail.oms.gateway.support.Infrastructure;
import com.retail.oms.gateway.support.KafkaTestClient;
import com.retail.oms.gateway.webhook.StripeSignature;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The adapter end to end inside its boundary: real Kafka, Schema Registry and Redis, the real Camel routes, and
 * WireMock as Stripe and PayPal. Each test uses its own idempotency key / order id, and the breakers are reset.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class GatewayAdapterIT {

    static final Duration WAIT = Duration.ofSeconds(25);
    static KafkaTestClient kafka;

    @Autowired StringRedisTemplate redis;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired TestRestTemplate http;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        Infrastructure.register(registry);
    }

    @BeforeAll
    static void client() {
        kafka = new KafkaTestClient();
    }

    @AfterAll
    static void close() {
        kafka.close();
    }

    @BeforeEach
    void reset() {
        Infrastructure.GATEWAYS.resetAll();
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        // PayPal token endpoint, used by every PayPal call
        Infrastructure.GATEWAYS.stubFor(post(urlEqualTo("/v1/oauth2/token")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"A21-it\",\"token_type\":\"Bearer\",\"expires_in\":32400}")));
    }

    @Test
    void duplicateAuthorizeWithTheSameKeyCallsStripeOnce() throws Exception {
        String key = "auth-" + UUID.randomUUID();
        stripeAuthorizeAnswers(200, "{\"id\":\"pi_it_1\",\"status\":\"requires_capture\",\"latest_charge\":\"ch_1\","
                + "\"payment_method\":{\"id\":\"pm_card_visa\",\"card\":{\"last4\":\"4242\"}}}");
        GatewayCommand first = authorize("STRIPE", key, "pm_card_visa");
        GatewayCommand again = GatewayCommand.newBuilder(first).setAttempt(2).build();

        try (var results = kafka.reader(Topics.PAYMENT_GATEWAY_RESULT)) {
            kafka.send(Topics.PAYMENT_GATEWAY_CMD, first.getOrderId(), first);
            var one = (GatewayResult) results.await(first.getOrderId(), 1, WAIT).get(0).value();
            kafka.send(Topics.PAYMENT_GATEWAY_CMD, again.getOrderId(), again);
            var both = results.await(first.getOrderId(), 2, WAIT);
            var two = (GatewayResult) both.get(1).value();

            assertThat(one.getOutcome()).isEqualTo("SUCCEEDED");
            assertThat(one.getGatewayRef()).isEqualTo("pi_it_1");
            assertThat(one.getCardLast4()).isEqualTo("4242");
            assertThat(one.getReplayed()).isFalse();
            assertThat(two.getOutcome()).isEqualTo("SUCCEEDED");
            assertThat(two.getGatewayRef()).isEqualTo("pi_it_1");
            assertThat(two.getReplayed()).as("answered from Redis").isTrue();
            assertThat(two.getEventId()).isNotEqualTo(one.getEventId());
        }
        Infrastructure.GATEWAYS.verify(1, postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", equalTo(key)));
        assertThat(redis.getExpire("pay:" + key)).as("pay:{key} kept ~24 h").isBetween(86_000L, 86_400L);
    }

    @Test
    void paypalDeclineIsFinalAndNotRetried() throws Exception {
        String key = "auth-" + UUID.randomUUID();
        Infrastructure.GATEWAYS.stubFor(post(urlPathMatching("/v2/checkout/orders/PAYPAL-DECLINE-1/authorize"))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"UNPROCESSABLE_ENTITY\",\"details\":[{\"issue\":\"INSTRUMENT_DECLINED\",\"description\":\"declined\"}]}")));
        GatewayCommand cmd = authorize("PAYPAL", key, "PAYPAL-DECLINE-1");

        try (var results = kafka.reader(Topics.PAYMENT_GATEWAY_RESULT)) {
            kafka.send(Topics.PAYMENT_GATEWAY_CMD, cmd.getOrderId(), cmd);
            kafka.send(Topics.PAYMENT_GATEWAY_CMD, cmd.getOrderId(), cmd);
            var got = results.await(cmd.getOrderId(), 2, WAIT);
            var first = (GatewayResult) got.get(0).value();
            assertThat(first.getOutcome()).isEqualTo("DECLINED");
            assertThat(first.getDeclineCode()).isEqualTo("INSTRUMENT_DECLINED");
            assertThat(((GatewayResult) got.get(1).value()).getReplayed()).isTrue();
        }
        Infrastructure.GATEWAYS.verify(1, postRequestedFor(urlPathMatching("/v2/checkout/orders/.*/authorize"))
                .withHeader("PayPal-Request-Id", equalTo(key)).withHeader("Authorization", equalTo("Bearer A21-it")));
    }

    @Test
    void slowGatewayHitsTheTimeLimiterAndTheRetryUsesTheSameKey() throws Exception {
        String key = "auth-" + UUID.randomUUID();
        stripeAuthorizeAnswers(200, "{\"id\":\"pi_slow\",\"status\":\"requires_capture\"}", 4000);   // limiter is 2 s
        GatewayCommand cmd = authorize("STRIPE", key, "pm_card_visa");

        try (var results = kafka.reader(Topics.PAYMENT_GATEWAY_RESULT)) {
            long start = System.nanoTime();
            kafka.send(Topics.PAYMENT_GATEWAY_CMD, cmd.getOrderId(), cmd);
            var pending = (GatewayResult) results.await(cmd.getOrderId(), 1, WAIT).get(0).value();
            assertThat(pending.getOutcome()).isEqualTo("PENDING_RETRY");
            assertThat(pending.getMessage()).contains("Timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
            assertThat(redis.hasKey("pay:" + key)).as("key freed for the retry").isFalse();

            stripeAuthorizeAnswers(200, "{\"id\":\"pi_slow\",\"status\":\"requires_capture\"}", 0);
            kafka.send(Topics.PAYMENT_GATEWAY_CMD, cmd.getOrderId(), GatewayCommand.newBuilder(cmd).setAttempt(2).build());
            var done = (GatewayResult) results.await(cmd.getOrderId(), 2, WAIT).get(1).value();
            assertThat(done.getOutcome()).isEqualTo("SUCCEEDED");
            assertThat(done.getReplayed()).isFalse();
        }
        Infrastructure.GATEWAYS.verify(2, postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", equalTo(key)));
    }

    @Test
    void circuitOpensAfterFailuresAndThenStopsCallingTheGateway() throws Exception {
        Infrastructure.GATEWAYS.stubFor(post(urlPathMatching("/v2/checkout/orders/.*/authorize"))
                .willReturn(aResponse().withStatus(503)));
        String orderId = UUID.randomUUID().toString();
        try (var results = kafka.reader(Topics.PAYMENT_GATEWAY_RESULT)) {
            for (int i = 0; i < 6; i++) {
                var cmd = GatewayCommand.newBuilder(authorize("PAYPAL", "auth-" + UUID.randomUUID(), "PAYPAL-DOWN-" + i))
                        .setOrderId(orderId).build();
                kafka.send(Topics.PAYMENT_GATEWAY_CMD, orderId, cmd);
            }
            var got = results.await(orderId, 6, WAIT);
            assertThat(got).extracting(r -> ((GatewayResult) r.value()).getOutcome()).containsOnly("PENDING_RETRY");
            assertThat(((GatewayResult) got.get(5).value()).getMessage()).contains("CallNotPermitted");
        }
        assertThat(breakers.circuitBreaker("paypal").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        // 4 calls fill the window (100 % failures) -> open; calls 5 and 6 never reach PayPal
        Infrastructure.GATEWAYS.verify(4, postRequestedFor(urlPathMatching("/v2/checkout/orders/.*/authorize")));
        assertThat(breakers.circuitBreaker("stripe").getState()).as("one breaker per gateway").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void declinesDoNotOpenTheCircuit() throws Exception {
        Infrastructure.GATEWAYS.stubFor(post(urlPathMatching("/v2/checkout/orders/.*/authorize"))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                        .withBody("{\"details\":[{\"issue\":\"INSTRUMENT_DECLINED\"}]}")));
        String orderId = UUID.randomUUID().toString();
        try (var results = kafka.reader(Topics.PAYMENT_GATEWAY_RESULT)) {
            for (int i = 0; i < 6; i++) {
                var cmd = GatewayCommand.newBuilder(authorize("PAYPAL", "auth-" + UUID.randomUUID(), "PAYPAL-DECLINE-" + i))
                        .setOrderId(orderId).build();
                kafka.send(Topics.PAYMENT_GATEWAY_CMD, orderId, cmd);
            }
            assertThat(results.await(orderId, 6, WAIT)).extracting(r -> ((GatewayResult) r.value()).getOutcome())
                    .containsOnly("DECLINED");
        }
        assertThat(breakers.circuitBreaker("paypal").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void captureVoidAndRefundGoToTheRightEndpoints() throws Exception {
        Infrastructure.GATEWAYS.stubFor(post(urlEqualTo("/v1/payment_intents/pi_c/capture"))
                .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"pi_c\",\"status\":\"requires_capture\",\"latest_charge\":\"ch_c\"}")));
        Infrastructure.GATEWAYS.stubFor(post(urlEqualTo("/v2/payments/authorizations/AUTH-9/void")).willReturn(aResponse().withStatus(204)));
        Infrastructure.GATEWAYS.stubFor(post(urlEqualTo("/v2/payments/captures/CAP-9/refund")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"REF-9\",\"status\":\"PENDING\"}")));
        String orderId = UUID.randomUUID().toString();
        var capture = command("CAPTURE", "STRIPE", orderId).setGatewayRef("pi_c").setAmount(new BigDecimal("40.10")).setFinalCapture(false).build();
        var voidIt = command("VOID", "PAYPAL", orderId).setGatewayRef("AUTH-9").build();
        var refund = command("REFUND", "PAYPAL", orderId).setGatewayRef("AUTH-9").setCaptureRef("CAP-9").setAmount(new BigDecimal("12.50")).build();
        var invalid = command("REFUND", "PAYPAL", orderId).setGatewayRef("AUTH-9").build();       // no capture id

        try (var results = kafka.reader(Topics.PAYMENT_GATEWAY_RESULT)) {
            for (var cmd : List.of(capture, voidIt, refund, invalid)) {
                kafka.send(Topics.PAYMENT_GATEWAY_CMD, orderId, cmd);
            }
            var got = results.await(orderId, 4, WAIT).stream().map(r -> (GatewayResult) r.value()).toList();
            assertThat(got).extracting(GatewayResult::getOperation, GatewayResult::getOutcome).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("CAPTURE", "SUCCEEDED"), org.assertj.core.groups.Tuple.tuple("VOID", "SUCCEEDED"),
                    org.assertj.core.groups.Tuple.tuple("REFUND", "SUCCEEDED"), org.assertj.core.groups.Tuple.tuple("REFUND", "ERROR"));
            assertThat(got.get(0).getGatewayTxnId()).isEqualTo("ch_c");
            assertThat(got.get(2).getGatewayTxnId()).isEqualTo("REF-9");
            assertThat(got.get(2).getGatewayStatus()).isEqualTo("PENDING");
            assertThat(got.get(3).getMessage()).contains("capture id");
        }
        Infrastructure.GATEWAYS.verify(postRequestedFor(urlEqualTo("/v1/payment_intents/pi_c/capture"))
                .withRequestBody(containing("amount_to_capture=4010")).withRequestBody(containing("final_capture=false")));
        Infrastructure.GATEWAYS.verify(postRequestedFor(urlEqualTo("/v2/payments/captures/CAP-9/refund"))
                .withRequestBody(matchingJsonPath("$.amount.value", equalTo("12.50"))));
    }

    @Test
    void poisonPillGoesToTheDlqAndTheNextCommandStillWorks() throws Exception {
        String key = UUID.randomUUID().toString();
        stripeAuthorizeAnswers(200, "{\"id\":\"pi_after\",\"status\":\"requires_capture\"}");
        GatewayCommand next = GatewayCommand.newBuilder(authorize("STRIPE", "auth-" + key, "pm_card_visa")).setOrderId(key).build();
        try (var dlq = kafka.rawReader("oms.payment-gateway-adapter.dlq"); var results = kafka.reader(Topics.PAYMENT_GATEWAY_RESULT)) {
            kafka.sendRaw(Topics.PAYMENT_GATEWAY_CMD, key, "garbage".getBytes(StandardCharsets.UTF_8));
            kafka.send(Topics.PAYMENT_GATEWAY_CMD, key, next);
            assertThat((byte[]) dlq.await(key, 1, WAIT).get(0).value()).isEqualTo("garbage".getBytes(StandardCharsets.UTF_8));
            assertThat(((GatewayResult) results.await(key, 1, WAIT).get(0).value()).getOutcome()).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void stripeWebhookIsVerifiedAndPublishedOnceEvenWhenSentTwice() throws Exception {
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = ("{\"id\":\"" + eventId + "\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"re_1\","
                + "\"object\":\"refund\",\"status\":\"succeeded\",\"payment_intent\":\"pi_1\",\"amount\":1500}}}")
                .getBytes(StandardCharsets.UTF_8);
        var signer = new StripeSignature("whsec_it_secret", Duration.ofMinutes(5), Clock.systemUTC());
        String signature = signer.header(Instant.now().getEpochSecond(), body);

        try (var events = kafka.reader(Topics.PAYMENT_GATEWAY_EVENT)) {
            var first = postWebhook("/webhooks/stripe", body, "Stripe-Signature", signature);
            var second = postWebhook("/webhooks/stripe", body, "Stripe-Signature", signature);
            var forged = postWebhook("/webhooks/stripe", body, "Stripe-Signature",
                    new StripeSignature("whsec_wrong", Duration.ofMinutes(5), Clock.systemUTC()).header(Instant.now().getEpochSecond(), body));
            var old = postWebhook("/webhooks/stripe", body, "Stripe-Signature", signer.header(Instant.now().getEpochSecond() - 600, body));

            assertThat(first.getStatusCode().value()).isEqualTo(200);
            assertThat(first.getBody()).contains("\"duplicate\":false");
            assertThat(second.getStatusCode().value()).isEqualTo(200);
            assertThat(second.getBody()).contains("\"duplicate\":true");
            assertThat(forged.getStatusCode().value()).isEqualTo(400);
            assertThat(forged.getBody()).contains("WHK-400");
            assertThat(old.getStatusCode().value()).isEqualTo(400);

            var got = events.await(eventId, 1, WAIT);
            assertThat(events.settle(eventId, Duration.ofSeconds(3))).as("published once").hasSize(1);
            var event = (GatewayWebhookEvent) got.get(0).value();
            assertThat(event.getType()).isEqualTo("refund.updated");
            assertThat(event.getObjectId()).isEqualTo("re_1");
            assertThat(event.getPaymentRef()).isEqualTo("pi_1");
            assertThat(event.getAmountMinor()).isEqualTo(1500L);
        }
    }

    @Test
    void paypalWebhookIsVerifiedByPayPal() throws Exception {
        Infrastructure.GATEWAYS.stubFor(post(urlEqualTo("/v1/notifications/verify-webhook-signature"))
                .withRequestBody(matchingJsonPath("$.transmission_sig", equalTo("good-sig")))
                .withRequestBody(matchingJsonPath("$.webhook_id", equalTo("WH-MOCK-OMS")))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"verification_status\":\"SUCCESS\"}")));
        Infrastructure.GATEWAYS.stubFor(post(urlEqualTo("/v1/notifications/verify-webhook-signature")).atPriority(10)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"verification_status\":\"FAILURE\"}")));
        String eventId = "WH-" + UUID.randomUUID();
        byte[] body = ("{\"id\":\"" + eventId + "\",\"event_type\":\"PAYMENT.CAPTURE.REFUNDED\",\"resource\":{\"id\":\"REF-1\","
                + "\"status\":\"COMPLETED\",\"amount\":{\"currency_code\":\"USD\",\"value\":\"12.50\"}}}").getBytes(StandardCharsets.UTF_8);

        try (var events = kafka.reader(Topics.PAYMENT_GATEWAY_EVENT)) {
            assertThat(postPaypal(body, "bad-sig").getStatusCode().value()).isEqualTo(400);
            assertThat(postPaypal(body, "good-sig").getStatusCode().value()).isEqualTo(200);
            var event = (GatewayWebhookEvent) events.await(eventId, 1, WAIT).get(0).value();
            assertThat(event.getGateway()).isEqualTo("PAYPAL");
            assertThat(event.getObjectId()).isEqualTo("REF-1");
            assertThat(event.getAmountMinor()).isEqualTo(1250L);
        }
    }

    // --- helpers

    private void stripeAuthorizeAnswers(int status, String body) {
        stripeAuthorizeAnswers(status, body, 0);
    }

    private void stripeAuthorizeAnswers(int status, String body, int delayMs) {
        Infrastructure.GATEWAYS.stubFor(post(urlEqualTo("/v1/payment_intents")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body).withFixedDelay(delayMs)));
    }

    private ResponseEntity<String> postWebhook(String path, byte[] body, String header, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(header, value);
        return http.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postPaypal(byte[] body, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("PAYPAL-AUTH-ALGO", "SHA256withRSA");
        headers.set("PAYPAL-CERT-URL", "https://api.sandbox.paypal.com/v1/notifications/certs/CERT-1");
        headers.set("PAYPAL-TRANSMISSION-ID", UUID.randomUUID().toString());
        headers.set("PAYPAL-TRANSMISSION-SIG", signature);
        headers.set("PAYPAL-TRANSMISSION-TIME", Instant.now().toString());
        return http.postForEntity("/webhooks/paypal", new HttpEntity<>(body, headers), String.class);
    }

    private static GatewayCommand authorize(String gateway, String key, String token) {
        return command("AUTHORIZE", gateway, UUID.randomUUID().toString()).setIdempotencyKey(key).setMethodToken(token).build();
    }

    private static GatewayCommand.Builder command(String op, String gateway, String orderId) {
        return GatewayCommand.newBuilder().setRequestId(UUID.randomUUID().toString()).setOccurredAt(Instant.now())
                .setIdempotencyKey(op.toLowerCase() + "-" + UUID.randomUUID()).setOperation(op).setGateway(gateway)
                .setPaymentId(UUID.randomUUID().toString()).setOrderId(orderId).setAmount(new BigDecimal("85.38"))
                .setCurrency("USD");
    }

    @SuppressWarnings("unused")
    private static String header(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
