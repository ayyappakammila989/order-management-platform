package com.retail.oms.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.order.support.PostgresTestConfig;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real tokens from a real Keycloak with the project's realm export (architecture 12.2: "real token accepted,
 * wrong role gets 403"). Calls go over HTTP to the running service, through the full filter chain.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "oms.order.schema-registry-url=mock://keycloak-it")
@Import(PostgresTestConfig.class)
class KeycloakSecurityIT {

    @Container
    static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.8.1")
            .withRealmImportFile("keycloak/oms-realm.json");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        String realm = KEYCLOAK.getAuthServerUrl() + "/realms/oms";
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> realm);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> realm + "/protocol/openid-connect/certs");
    }

    static String serviceToken;
    static String customerToken;
    static String customerSub;

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;

    RestClient http = RestClient.builder().defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build();

    @BeforeAll
    static void tokens() {
        serviceToken = clientCredentials("cart-service", "dev-cart-service-secret");
        customerToken = password("customer1");
        customerSub = claim(customerToken, "sub");
    }

    @Test
    void serviceTokenCreatesOrderAndOwnerReadsIt() throws Exception {
        String orderId = createOrder(serviceToken, customerSub).path("orderId").asText();

        var asOwner = get("/api/v1/orders/" + orderId, customerToken);
        assertThat(asOwner.status()).isEqualTo(200);
        assertThat(asOwner.body().path("customerId").asText()).isEqualTo(customerSub);
        assertThat(get("/api/v1/orders/" + orderId, password("agent1")).status()).isEqualTo(200);
    }

    @Test
    void customerTokenCannotCallInternalApi() throws Exception {
        var response = post("/internal/v1/orders", customerToken, orderBody(customerSub), UUID.randomUUID().toString());
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body().path("errorCode").asText()).isEqualTo("SEC-403");
    }

    @Test
    void customerCannotReadAnotherCustomersOrder() throws Exception {
        String orderId = createOrder(serviceToken, "someone-else").path("orderId").asText();

        var response = get("/api/v1/orders/" + orderId, customerToken);
        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void financeCannotCancelButSupervisorCan() throws Exception {
        String orderId = createOrder(serviceToken, customerSub).path("orderId").asText();
        String reason = "{\"reason\":\"duplicate order\"}";

        assertThat(post("/api/v1/orders/" + orderId + "/cancel", password("finance1"), reason, null).status())
                .isEqualTo(403);
        var bySupervisor = post("/api/v1/orders/" + orderId + "/cancel", password("supervisor1"), reason, null);
        assertThat(bySupervisor.status()).isEqualTo(200);
        assertThat(bySupervisor.body().path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void missingTamperedAndForeignRealmTokensAre401() throws Exception {
        String[] parts = customerToken.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1])).replace("CUSTOMER", "SERVICE").getBytes());
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];
        String masterRealmToken = KEYCLOAK.getKeycloakAdminClient().tokenManager().getAccessTokenString();

        assertThat(get("/api/v1/orders/" + UUID.randomUUID(), null).status()).isEqualTo(401);
        assertThat(get("/api/v1/orders/" + UUID.randomUUID(), tampered).status()).isEqualTo(401);
        var foreign = get("/api/v1/orders/" + UUID.randomUUID(), masterRealmToken);
        assertThat(foreign.status()).isEqualTo(401);
        assertThat(foreign.body().path("errorCode").asText()).isEqualTo("SEC-401");
    }

    // ---- helpers ----

    record Response(int status, JsonNode body) {
    }

    private JsonNode createOrder(String token, String customerId) throws Exception {
        var response = post("/internal/v1/orders", token, orderBody(customerId), "kc-it-" + UUID.randomUUID());
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        return response.body();
    }

    private static String orderBody(String customerId) {
        return """
                {"customerId":"%s","channel":"WEB","currency":"USD",
                 "lines":[{"sku":"SKU-KC","quantity":1,"unitPrice":12.00,"fulfillmentType":"SHIP"}],
                 "shippingAddress":{"name":"K C","line1":"1 Auth St","city":"Boston","state":"MA","zip":"02101"},
                 "shippingTotal":0.00}""".formatted(customerId);
    }

    private Response get(String path, String token) throws Exception {
        var spec = http.get().uri("http://localhost:" + port + path);
        if (token != null) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return spec.exchange((req, res) -> new Response(res.getStatusCode().value(), readBody(res.getBody())));
    }

    private Response post(String path, String token, String body, String idempotencyKey) throws Exception {
        var spec = http.post().uri("http://localhost:" + port + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(body);
        if (idempotencyKey != null) {
            spec.header("Idempotency-Key", idempotencyKey);
        }
        return spec.exchange((req, res) -> new Response(res.getStatusCode().value(), readBody(res.getBody())));
    }

    private JsonNode readBody(java.io.InputStream in) throws java.io.IOException {
        byte[] bytes = in.readAllBytes();
        return bytes.length == 0 ? objectMapper.createObjectNode() : objectMapper.readTree(bytes);
    }

    private static String clientCredentials(String clientId, String secret) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", secret);
        return token(form);
    }

    /** oms-dev-cli is the dev-only client that allows the password grant (see docs/local-access.md). */
    private static String password(String username) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "oms-dev-cli");
        form.add("username", username);
        form.add("password", "Passw0rd!");
        return token(form);
    }

    private static String token(LinkedMultiValueMap<String, String> form) {
        JsonNode json = RestClient.create().post()
                .uri(KEYCLOAK.getAuthServerUrl() + "/realms/oms/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(JsonNode.class);
        return json.get("access_token").asText();
    }

    private static String claim(String jwt, String name) {
        try {
            return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1])).get(name).asText();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
