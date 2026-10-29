package com.retail.oms.gateway.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.events.payment.GatewayCommand;
import com.retail.oms.events.payment.GatewayResult;
import com.retail.oms.gateway.application.HttpCall;
import com.retail.oms.gateway.config.GatewayProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Our Stripe requests against the real stripe-mock (same image as docker-compose.yml). stripe-mock validates every
 * parameter against Stripe's OpenAPI spec, so a misspelt field or a wrong value type fails here even though
 * WireMock would accept it.
 */
@Testcontainers
class StripeMockContractIT {

    @Container
    static final GenericContainer<?> STRIPE_MOCK = new GenericContainer<>("stripe/stripe-mock:v0.206.0")
            .withExposedPorts(12111).waitingFor(Wait.forListeningPort());

    final HttpClient http = HttpClient.newHttpClient();

    StripeApi api() {
        var props = new GatewayProperties(
                new GatewayProperties.Stripe("http://" + STRIPE_MOCK.getHost() + ":" + STRIPE_MOCK.getMappedPort(12111),
                        "sk_test_contract", "whsec_x", Duration.ofMinutes(5), true),
                null, Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofHours(24), Duration.ofSeconds(60),
                new GatewayProperties.Breaker(50, 20, 20, Duration.ofSeconds(30)),
                new GatewayProperties.Webhooks(Duration.ofHours(72), List.of()), 3, "dlq", 1);
        return new StripeApi(props, new ObjectMapper(), Clock.systemUTC());
    }

    @Test
    void allFourOperationsAreValidStripeRequests() throws Exception {
        StripeApi stripe = api();
        var base = GatewayCommand.newBuilder().setRequestId("t").setOccurredAt(Instant.now()).setIdempotencyKey("k-1")
                .setGateway("STRIPE").setPaymentId("p-1").setOrderId("o-1").setAmount(new BigDecimal("85.38"))
                .setCurrency("USD").setMethodToken("pm_card_visa");

        GatewayResult auth = send(stripe, base.setOperation("AUTHORIZE").build());
        assertThat(auth.getOutcome()).isEqualTo("SUCCEEDED");
        assertThat(auth.getGatewayRef()).startsWith("pi_");
        // stripe-mock does not expand payment_method (real Stripe does; the mapping is in GatewayApiMappingTest)
        assertThat(auth.getCardLast4()).isNull();

        var withRef = GatewayCommand.newBuilder(base.build()).setGatewayRef(auth.getGatewayRef());
        assertThat(send(stripe, withRef.setOperation("CAPTURE").setAmount(new BigDecimal("40.10")).setFinalCapture(false).build())
                .getOutcome()).isEqualTo("SUCCEEDED");
        assertThat(send(stripe, withRef.setOperation("VOID").build()).getOutcome()).isEqualTo("SUCCEEDED");
        GatewayResult refund = send(stripe, withRef.setOperation("REFUND").setAmount(new BigDecimal("15.00")).build());
        assertThat(refund.getOutcome()).isEqualTo("SUCCEEDED");
        assertThat(refund.getGatewayTxnId()).startsWith("re_");
    }

    @Test
    void invalidRequestIsAnErrorNotARetry() throws Exception {
        var cmd = GatewayCommand.newBuilder().setRequestId("t").setOccurredAt(Instant.now()).setIdempotencyKey("k-2")
                .setGateway("STRIPE").setPaymentId("p-1").setOrderId("o-1").setAmount(new BigDecimal("1.00"))
                .setCurrency("USD").setOperation("CAPTURE").setGatewayRef("pi_1").build();
        HttpCall call = api().request(cmd);
        // a parameter Stripe does not know: stripe-mock answers 400 invalid_request_error
        var broken = new HttpCall(call.method(), call.url(), call.headers(), call.body() + "&no_such_param=1");
        GatewayResult result = exchange(api(), cmd, broken);
        assertThat(result.getOutcome()).isEqualTo("ERROR");
        assertThat(result.getHttpStatus()).isEqualTo(400);
    }

    private GatewayResult send(StripeApi stripe, GatewayCommand cmd) throws Exception {
        return exchange(stripe, cmd, stripe.request(cmd));
    }

    private GatewayResult exchange(StripeApi stripe, GatewayCommand cmd, HttpCall call) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(call.url())).POST(HttpRequest.BodyPublishers.ofString(call.body()));
        call.headers().forEach(builder::header);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return stripe.response(cmd, response.statusCode(), response.body());
    }
}
