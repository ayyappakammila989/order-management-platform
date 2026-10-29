package com.retail.oms.gateway.paypal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.gateway.application.GatewayUnavailableException;
import com.retail.oms.gateway.config.GatewayProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** PayPal OAuth2 client-credentials token, cached until 60 s before it expires (like kc:token:{client}). */
@Component
public class PayPalTokens {

    private record Token(String value, Instant refreshAt) {
    }

    private final GatewayProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http;
    private volatile Token token;

    public PayPalTokens(GatewayProperties props, ObjectMapper json, Clock clock) {
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
    }

    public String get() {
        Token t = token;
        if (t != null && Instant.now(clock).isBefore(t.refreshAt())) {
            return t.value();
        }
        synchronized (this) {
            if (token == null || !Instant.now(clock).isBefore(token.refreshAt())) {
                token = fetch();
            }
            return token.value();
        }
    }

    /** After a 401 from the API: the next call fetches a new token. */
    public void invalidate() {
        token = null;
    }

    private Token fetch() {
        var p = props.paypal();
        String basic = Base64.getEncoder().encodeToString((p.clientId() + ":" + p.clientSecret()).getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(p.baseUrl() + "/v1/oauth2/token"))
                .timeout(props.callTimeout())
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new GatewayUnavailableException("PayPal token endpoint answered " + response.statusCode());
            }
            JsonNode body = json.readTree(response.body());
            long expiresIn = body.path("expires_in").asLong(300);
            return new Token(body.path("access_token").asText(), Instant.now(clock).plusSeconds(Math.max(0, expiresIn - 60)));
        } catch (java.io.IOException e) {
            throw new GatewayUnavailableException("PayPal token endpoint unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayUnavailableException("Interrupted while fetching a PayPal token", e);
        }
    }
}
