package com.retail.oms.gateway.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.retail.oms.gateway.application.GatewayUnavailableException;
import com.retail.oms.gateway.config.GatewayProperties;
import com.retail.oms.gateway.paypal.PayPalTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * PayPal webhooks are verified by PayPal itself (architecture 7.2: "PayPal verify API"):
 * POST /v1/notifications/verify-webhook-signature with the PAYPAL-* headers, our webhook id and the event.
 * PayPal not reachable -> GatewayUnavailableException -> 503, so PayPal sends the webhook again later.
 */
@Component
public class PayPalWebhookVerifier {

    private final GatewayProperties props;
    private final PayPalTokens tokens;
    private final ObjectMapper json;
    private final HttpClient http;

    public PayPalWebhookVerifier(GatewayProperties props, PayPalTokens tokens, ObjectMapper json) {
        this.props = props;
        this.tokens = tokens;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
    }

    public void verify(Map<String, String> headers, JsonNode event) {
        ObjectNode body = json.createObjectNode()
                .put("auth_algo", header(headers, "paypal-auth-algo"))
                .put("cert_url", header(headers, "paypal-cert-url"))
                .put("transmission_id", header(headers, "paypal-transmission-id"))
                .put("transmission_sig", header(headers, "paypal-transmission-sig"))
                .put("transmission_time", header(headers, "paypal-transmission-time"))
                .put("webhook_id", props.paypal().webhookId());
        body.set("webhook_event", event);
        try {
            var request = HttpRequest.newBuilder(URI.create(props.paypal().baseUrl() + "/v1/notifications/verify-webhook-signature"))
                    .timeout(props.callTimeout())
                    .header("Authorization", "Bearer " + tokens.get())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                tokens.invalidate();
            }
            if (response.statusCode() != 200) {
                throw new GatewayUnavailableException("PayPal verify API answered " + response.statusCode());
            }
            String status = json.readTree(response.body()).path("verification_status").asText();
            if (!"SUCCESS".equals(status)) {
                throw new WebhookRejectedException("PayPal says the webhook signature is " + status);
            }
        } catch (java.io.IOException e) {
            throw new GatewayUnavailableException("PayPal verify API unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayUnavailableException("Interrupted", e);
        }
    }

    private static String header(Map<String, String> headers, String name) {
        String value = headers.get(name);
        if (value == null || value.isBlank()) {
            throw new WebhookRejectedException("Header " + name.toUpperCase(java.util.Locale.ROOT) + " missing");
        }
        return value;
    }
}
