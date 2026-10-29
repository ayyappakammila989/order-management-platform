package com.retail.oms.gateway.paypal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.events.payment.GatewayCommand;
import com.retail.oms.events.payment.GatewayResult;
import com.retail.oms.gateway.application.GatewayUnavailableException;
import com.retail.oms.gateway.application.HttpCall;
import com.retail.oms.gateway.application.Money;
import com.retail.oms.gateway.application.Outcome;
import com.retail.oms.gateway.application.Results;
import com.retail.oms.gateway.config.GatewayProperties;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * PayPal REST (architecture 5, route paypal-payment): authorize an order the buyer approved on the client
 * (the method token is that order id), capture / void the authorization, refund a capture.
 * PayPal-Request-Id makes each call idempotent at PayPal, like Stripe's Idempotency-Key.
 */
@Component
public class PayPalApi {

    private final GatewayProperties.PayPal props;
    private final PayPalTokens tokens;
    private final ObjectMapper json;
    private final Clock clock;

    public PayPalApi(GatewayProperties props, PayPalTokens tokens, ObjectMapper json, Clock clock) {
        this.props = props.paypal();
        this.tokens = tokens;
        this.json = json;
        this.clock = clock;
    }

    public HttpCall request(GatewayCommand cmd) {
        String amount = "{\"amount\":{\"currency_code\":\"" + cmd.getCurrency() + "\",\"value\":\"" + Money.plain(cmd.getAmount()) + "\"}";
        String[] pathAndBody = switch (cmd.getOperation()) {
            // the amount was fixed when the PayPal order was created; it is sent here only so the mock can echo it
            case "AUTHORIZE" -> new String[] {"/v2/checkout/orders/" + cmd.getMethodToken() + "/authorize", amount + "}"};
            case "CAPTURE" -> new String[] {"/v2/payments/authorizations/" + ref(cmd.getGatewayRef(), cmd) + "/capture",
                    amount + ",\"final_capture\":" + cmd.getFinalCapture() + "}"};
            case "VOID" -> new String[] {"/v2/payments/authorizations/" + ref(cmd.getGatewayRef(), cmd) + "/void", ""};
            case "REFUND" -> new String[] {"/v2/payments/captures/" + ref(cmd.getCaptureRef(), cmd) + "/refund", amount + "}"};
            default -> throw new IllegalArgumentException("Unknown operation " + cmd.getOperation());
        };
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + tokens.get(),
                "PayPal-Request-Id", cmd.getIdempotencyKey(),
                "Content-Type", "application/json",
                "Prefer", "return=representation");
        return new HttpCall("POST", props.baseUrl() + pathAndBody[0], headers, pathAndBody[1]);
    }

    /** 2xx -> SUCCEEDED; 422 -> DECLINED (e.g. INSTRUMENT_DECLINED); 401 -> new token, retry; 429/5xx -> unavailable. */
    public GatewayResult response(GatewayCommand cmd, int status, String body) {
        if (status == 401) {
            tokens.invalidate();
            throw new GatewayUnavailableException("PayPal rejected the access token");
        }
        if (status == 429 || status >= 500) {
            throw new GatewayUnavailableException("PayPal answered " + status);
        }
        JsonNode node = parse(body);
        if (status >= 400) {
            JsonNode detail = node.path("details").path(0);
            String issue = detail.path("issue").asText(node.path("name").asText(null));
            return Results.of(cmd, status == 422 ? Outcome.DECLINED : Outcome.ERROR, clock).setHttpStatus(status)
                    .setDeclineCode(issue).setMessage(Results.trim(detail.path("description").asText(node.path("message").asText(null))))
                    .build();
        }
        GatewayResult.Builder result = Results.of(cmd, Outcome.SUCCEEDED, clock).setHttpStatus(status);
        switch (cmd.getOperation()) {
            case "AUTHORIZE" -> {
                JsonNode auth = node.path("purchase_units").path(0).path("payments").path("authorizations").path(0);
                if (auth.isMissingNode()) {
                    throw new GatewayUnavailableException("PayPal authorize answer has no authorization");
                }
                result.setGatewayRef(auth.path("id").asText()).setGatewayStatus(auth.path("status").asText(null));
            }
            case "CAPTURE", "REFUND" -> result.setGatewayTxnId(node.path("id").asText(null))
                    .setGatewayStatus(node.path("status").asText(null));
            case "VOID" -> result.setGatewayStatus("VOIDED");
            default -> {
            }
        }
        return result.build();
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (java.io.IOException e) {
            throw new GatewayUnavailableException("PayPal answered something that is not JSON", e);
        }
    }

    private static String ref(String value, GatewayCommand cmd) {
        if (value == null) {
            throw new IllegalArgumentException(cmd.getOperation() + " needs a PayPal authorization / capture id");
        }
        return value;
    }
}
