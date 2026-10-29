package com.retail.oms.gateway.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.events.payment.GatewayCommand;
import com.retail.oms.events.payment.GatewayResult;
import com.retail.oms.gateway.application.GatewayUnavailableException;
import com.retail.oms.gateway.config.GatewayProperties;
import com.retail.oms.gateway.paypal.PayPalApi;
import com.retail.oms.gateway.paypal.PayPalTokens;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Requests and answer mapping for both gateways, without HTTP (architecture 12.1 "mappers"). */
class GatewayApiMappingTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Clock CLOCK = Clock.systemUTC();

    static GatewayProperties props(boolean mockMode) {
        return new GatewayProperties(
                new GatewayProperties.Stripe("http://stripe", "sk_test_x", "whsec_x", Duration.ofMinutes(5), mockMode),
                new GatewayProperties.PayPal("http://paypal", "id", "secret", "WH-1"),
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofHours(24), Duration.ofSeconds(60),
                new GatewayProperties.Breaker(50, 20, 20, Duration.ofSeconds(30)),
                new GatewayProperties.Webhooks(Duration.ofHours(72), List.of("127.0.0.1/32")), 3, "dlq", 1);
    }

    static GatewayCommand.Builder cmd(String op, String gateway) {
        return GatewayCommand.newBuilder().setRequestId("txn-1").setOccurredAt(Instant.now()).setIdempotencyKey("auth-o1")
                .setOperation(op).setGateway(gateway).setPaymentId("p-1").setOrderId("o-1")
                .setAmount(new BigDecimal("85.38")).setCurrency("USD").setMethodToken("pm_card_visa").setGatewayRef("pi_1")
                .setCaptureRef("CAP-1");
    }

    final StripeApi stripe = new StripeApi(props(false), JSON, CLOCK);

    @Test
    void stripeAuthorizeIsAManualCaptureConfirmWithTheIdempotencyKey() {
        var call = stripe.request(cmd("AUTHORIZE", "STRIPE").build());
        Map<String, String> form = form(call.body());

        assertThat(call.url()).isEqualTo("http://stripe/v1/payment_intents");
        assertThat(call.headers()).containsEntry("Idempotency-Key", "auth-o1").containsEntry("Authorization", "Bearer sk_test_x");
        assertThat(form).containsEntry("amount", "8538").containsEntry("currency", "usd")
                .containsEntry("capture_method", "manual").containsEntry("confirm", "true")
                .containsEntry("payment_method", "pm_card_visa").containsEntry("metadata[order_id]", "o-1");
    }

    @Test
    void stripePartialCaptureVoidAndRefund() {
        var capture = stripe.request(cmd("CAPTURE", "STRIPE").setAmount(new BigDecimal("40.10")).setFinalCapture(false).build());
        assertThat(capture.url()).endsWith("/v1/payment_intents/pi_1/capture");
        assertThat(form(capture.body())).containsEntry("amount_to_capture", "4010").containsEntry("final_capture", "false");
        assertThat(form(stripe.request(cmd("CAPTURE", "STRIPE").build()).body())).doesNotContainKey("final_capture");

        assertThat(stripe.request(cmd("VOID", "STRIPE").build()).url()).endsWith("/v1/payment_intents/pi_1/cancel");
        var refund = stripe.request(cmd("REFUND", "STRIPE").setAmount(new BigDecimal("15.00")).build());
        assertThat(refund.url()).endsWith("/v1/refunds");
        assertThat(form(refund.body())).containsEntry("payment_intent", "pi_1").containsEntry("amount", "1500");
    }

    @Test
    void stripeAnswers() {
        var auth = cmd("AUTHORIZE", "STRIPE").build();
        GatewayResult ok = stripe.response(auth, 200, """
                {"id":"pi_9","status":"requires_capture","latest_charge":"ch_9","payment_method":{"card":{"last4":"4242"}}}""");
        assertThat(ok.getOutcome()).isEqualTo("SUCCEEDED");
        assertThat(ok.getGatewayRef()).isEqualTo("pi_9");
        assertThat(ok.getCardLast4()).isEqualTo("4242");

        GatewayResult declined = stripe.response(auth, 402, """
                {"error":{"type":"card_error","code":"card_declined","decline_code":"insufficient_funds","message":"Your card has insufficient funds."}}""");
        assertThat(declined.getOutcome()).isEqualTo("DECLINED");
        assertThat(declined.getDeclineCode()).isEqualTo("insufficient_funds");

        GatewayResult invalid = stripe.response(auth, 400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"No such PaymentMethod\"}}");
        assertThat(invalid.getOutcome()).isEqualTo("ERROR");

        // real Stripe (mock-mode off): a confirm that ended in requires_action is not an authorization
        GatewayResult needs3ds = stripe.response(auth, 200, "{\"id\":\"pi_9\",\"status\":\"requires_action\"}");
        assertThat(needs3ds.getOutcome()).isEqualTo("DECLINED");
        // stripe-mock (mock-mode on) always says requires_payment_method: 2xx counts
        var mock = new StripeApi(props(true), JSON, CLOCK);
        assertThat(mock.response(auth, 200, "{\"id\":\"pi_9\",\"status\":\"requires_payment_method\"}").getOutcome())
                .isEqualTo("SUCCEEDED");

        assertThatThrownBy(() -> stripe.response(auth, 503, "")).isInstanceOf(GatewayUnavailableException.class);
        assertThatThrownBy(() -> stripe.response(auth, 429, "{}")).isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    void paypalRequestsAndAnswers() {
        PayPalTokens tokens = mock(PayPalTokens.class);
        when(tokens.get()).thenReturn("A21-token");
        var paypal = new PayPalApi(props(true), tokens, JSON, CLOCK);
        var auth = cmd("AUTHORIZE", "PAYPAL").setMethodToken("PAYPAL-ORDER-1").build();

        var call = paypal.request(auth);
        assertThat(call.url()).isEqualTo("http://paypal/v2/checkout/orders/PAYPAL-ORDER-1/authorize");
        assertThat(call.headers()).containsEntry("PayPal-Request-Id", "auth-o1").containsEntry("Authorization", "Bearer A21-token");
        assertThat(paypal.request(cmd("REFUND", "PAYPAL").build()).url()).endsWith("/v2/payments/captures/CAP-1/refund");
        assertThat(paypal.request(cmd("CAPTURE", "PAYPAL").setFinalCapture(false).build()).body()).contains("\"final_capture\":false");

        GatewayResult ok = paypal.response(auth, 201, """
                {"id":"PAYPAL-ORDER-1","status":"COMPLETED","purchase_units":[{"payments":{"authorizations":[{"id":"AUTH-7","status":"CREATED"}]}}]}""");
        assertThat(ok.getGatewayRef()).isEqualTo("AUTH-7");
        GatewayResult declined = paypal.response(auth, 422, """
                {"name":"UNPROCESSABLE_ENTITY","details":[{"issue":"INSTRUMENT_DECLINED","description":"declined"}]}""");
        assertThat(declined.getOutcome()).isEqualTo("DECLINED");
        assertThat(declined.getDeclineCode()).isEqualTo("INSTRUMENT_DECLINED");

        assertThatThrownBy(() -> paypal.response(auth, 401, "")).isInstanceOf(GatewayUnavailableException.class);
        verify(tokens).invalidate();
    }

    private static Map<String, String> form(String body) {
        return Arrays.stream(body.split("&")).map(p -> p.split("=", 2))
                .collect(Collectors.toMap(p -> URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                        p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
    }
}
