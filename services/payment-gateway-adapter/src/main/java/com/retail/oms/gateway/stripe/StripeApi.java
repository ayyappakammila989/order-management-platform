package com.retail.oms.gateway.stripe;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Stripe PaymentIntents API (architecture 5, route stripe-payment): authorize = create + confirm with
 * capture_method=manual, capture (partial with final_capture=false), void = cancel, refund = /v1/refunds.
 * Every call carries the Idempotency-Key header (Stripe keeps the answer for 24 h; stripe-mock does not).
 */
@Component
public class StripeApi {

    /** Statuses after a successful manual-capture confirm (requires_capture) and others that mean "money is there". */
    static final Set<String> AUTHORIZED = Set.of("requires_capture", "succeeded", "processing");

    private final GatewayProperties.Stripe props;
    private final ObjectMapper json;
    private final Clock clock;

    public StripeApi(GatewayProperties props, ObjectMapper json, Clock clock) {
        this.props = props.stripe();
        this.json = json;
        this.clock = clock;
    }

    public HttpCall request(GatewayCommand cmd) {
        Map<String, String> form = new LinkedHashMap<>();
        String path = switch (cmd.getOperation()) {
            case "AUTHORIZE" -> {
                form.put("amount", String.valueOf(Money.toMinor(cmd.getAmount())));
                form.put("currency", cmd.getCurrency().toLowerCase(java.util.Locale.ROOT));
                form.put("payment_method", cmd.getMethodToken());
                // server-side confirm without a browser: no redirect-based methods (else Stripe wants a return_url).
                // (payment_method_types is no longer a create parameter in the current API spec; stripe-mock rejects it)
                form.put("automatic_payment_methods[enabled]", "true");
                form.put("automatic_payment_methods[allow_redirects]", "never");
                form.put("confirm", "true");
                form.put("capture_method", "manual");
                // partial captures per shipment (architecture 3.4 step 7) need multicapture on the authorization
                form.put("payment_method_options[card][request_multicapture]", "if_available");
                form.put("expand[0]", "payment_method");
                form.put("metadata[order_id]", cmd.getOrderId());
                form.put("metadata[payment_id]", cmd.getPaymentId());
                yield "/v1/payment_intents";
            }
            case "CAPTURE" -> {
                form.put("amount_to_capture", String.valueOf(Money.toMinor(cmd.getAmount())));
                if (!cmd.getFinalCapture()) {
                    form.put("final_capture", "false");
                }
                yield "/v1/payment_intents/" + ref(cmd) + "/capture";
            }
            case "VOID" -> {
                form.put("cancellation_reason", "abandoned");
                yield "/v1/payment_intents/" + ref(cmd) + "/cancel";
            }
            case "REFUND" -> {
                form.put("payment_intent", ref(cmd));
                form.put("amount", String.valueOf(Money.toMinor(cmd.getAmount())));
                form.put("metadata[payment_id]", cmd.getPaymentId());
                yield "/v1/refunds";
            }
            default -> throw new IllegalArgumentException("Unknown operation " + cmd.getOperation());
        };
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + props.apiKey(),
                "Idempotency-Key", cmd.getIdempotencyKey(),
                "Content-Type", "application/x-www-form-urlencoded");
        return new HttpCall("POST", props.baseUrl() + path, headers, form(form));
    }

    /**
     * 2xx -> SUCCEEDED (or DECLINED if a real Stripe says the confirm did not authorize); 402 card_error -> DECLINED;
     * other 4xx -> ERROR (repeating will not help); 429 / 5xx -> GatewayUnavailableException (circuit breaker,
     * PENDING_RETRY).
     */
    public GatewayResult response(GatewayCommand cmd, int status, String body) {
        if (status == 429 || status >= 500) {
            throw new GatewayUnavailableException("Stripe answered " + status);
        }
        JsonNode node = parse(body);
        if (status >= 400) {
            JsonNode error = node.path("error");
            boolean declined = status == 402 || "card_error".equals(error.path("type").asText());
            String code = text(error, "decline_code") != null ? text(error, "decline_code") : text(error, "code");
            return Results.of(cmd, declined ? Outcome.DECLINED : Outcome.ERROR, clock).setHttpStatus(status)
                    .setDeclineCode(code).setMessage(Results.trim(text(error, "message"))).build();
        }
        GatewayResult.Builder result = Results.of(cmd, Outcome.SUCCEEDED, clock).setHttpStatus(status)
                .setGatewayStatus(text(node, "status"));
        switch (cmd.getOperation()) {
            case "AUTHORIZE" -> {
                result.setGatewayRef(text(node, "id")).setGatewayTxnId(text(node, "latest_charge"))
                        .setCardLast4(text(node.path("payment_method").path("card"), "last4"));
                if (!props.mockMode() && !AUTHORIZED.contains(text(node, "status"))) {
                    // e.g. requires_action (3-D Secure) or requires_payment_method (declined during confirm)
                    result.setOutcome(Outcome.DECLINED.name()).setDeclineCode(text(node, "status"))
                            .setMessage("Payment intent is " + text(node, "status") + " after confirm");
                }
            }
            case "CAPTURE" -> result.setGatewayTxnId(text(node, "latest_charge"));
            case "REFUND" -> result.setGatewayTxnId(text(node, "id"));
            default -> {
            }
        }
        return result.build();
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (java.io.IOException e) {
            throw new GatewayUnavailableException("Stripe answered something that is not JSON", e);
        }
    }

    private static String ref(GatewayCommand cmd) {
        if (cmd.getGatewayRef() == null) {
            throw new IllegalArgumentException(cmd.getOperation() + " needs the payment intent id");
        }
        return cmd.getGatewayRef();
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.isContainerNode() ? null : v.asText();
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
