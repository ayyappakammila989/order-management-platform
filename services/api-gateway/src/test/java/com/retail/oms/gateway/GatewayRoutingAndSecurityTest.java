package com.retail.oms.gateway;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.retail.oms.common.web.CorrelationId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Import(GatewayTestSupport.class)
class GatewayRoutingAndSecurityTest {

    @Autowired
    WebTestClient client;

    private WebTestClient as(String... roles) {
        GrantedAuthority[] authorities = java.util.Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toArray(GrantedAuthority[]::new);
        return client.mutateWith(mockJwt().authorities(authorities));
    }

    // ---------- authentication ----------

    @Test
    void apiCallWithoutTokenIs401InStandardFormat() {
        client.get().uri("/api/v1/web/orders").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(CorrelationId.HEADER)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.errorCode").isEqualTo("SEC-401")
                .jsonPath("$.path").isEqualTo("/api/v1/web/orders")
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    void garbageBearerTokenIs401() {
        client.get().uri("/api/v1/web/orders").header("Authorization", "Bearer not-a-jwt").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errorCode").isEqualTo("SEC-401");
    }

    // ---------- authorization by role ----------

    @Test
    void customerCannotReachAdminRoutes() {
        as("CUSTOMER").get().uri("/api/v1/admin/orders").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo("SEC-403");
    }

    @Test
    void supportAgentPassesAdminCheckAndGets503WhenBackendIsDown() {
        as("SUPPORT_AGENT").get().uri("/api/v1/admin/orders").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.errorCode").isEqualTo("SYS-503");
    }

    @Test
    void financeIsAnEmployeeButCannotUseChat() {
        as("FINANCE").get().uri("/api/v1/chat/ask").exchange().expectStatus().isForbidden();
        as("SUPPORT_AGENT").get().uri("/api/v1/chat/ask").exchange().expectStatus().isEqualTo(503);
    }

    // ---------- public paths ----------

    @Test
    void webhooksStoresAndFallbackNeedNoToken() {
        client.get().uri("/webhooks/stripe").exchange().expectStatus().isEqualTo(503);      // routed, backend down
        client.get().uri("/webhooks/carriers/ups").exchange().expectStatus().isEqualTo(503);
        client.get().uri("/api/v1/stores/12").exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void circuitBreakerFallbackGivesFriendly503() {
        client.get().uri("/fallback/mobile").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("SYS-503")
                .jsonPath("$.message").value(m -> org.assertj.core.api.Assertions.assertThat((String) m).contains("mobile"));
    }

    @Test
    void unknownPathWithTokenIs404InStandardFormat() {
        as("CUSTOMER").get().uri("/api/v1/nothing-here").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo("SYS-404");
    }

    // ---------- /internal/** is never reachable ----------

    @Test
    void internalPathsAreBlockedForEveryone() {
        for (String path : new String[] {"/internal/orders", "/INTERNAL/orders", "/api/v1/web/../internal/x", "/%69nternal/orders"}) {
            client.get().uri(java.net.URI.create(path)).exchange()
                    .expectStatus().isForbidden();
            as("SUPPORT_SUPERVISOR", "OPS_ADMIN").get().uri(java.net.URI.create(path)).exchange()
                    .expectStatus().isForbidden()
                    .expectBody().jsonPath("$.errorCode").isEqualTo("SEC-403");
        }
    }

    // ---------- correlation id and header hygiene ----------

    @Test
    void missingCorrelationIdIsGeneratedAndForwardedDownstream() {
        var result = as("CUSTOMER").get().uri("/test/echo").exchange()
                .expectStatus().isOk()
                .expectHeader().exists(CorrelationId.HEADER)
                .returnResult(java.util.Map.class);
        String onResponse = result.getResponseHeaders().getFirst(CorrelationId.HEADER);
        java.util.Map<?, ?> seenDownstream = result.getResponseBody().blockFirst();
        org.assertj.core.api.Assertions.assertThat(seenDownstream.get("x-correlation-id")).isEqualTo(onResponse);
        org.assertj.core.api.Assertions.assertThat(onResponse).hasSize(36);
    }

    @Test
    void clientCorrelationIdIsKept() {
        as("CUSTOMER").get().uri("/test/echo").header(CorrelationId.HEADER, "client-abc").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CorrelationId.HEADER, "client-abc")
                .expectBody().jsonPath("$['x-correlation-id']").isEqualTo("client-abc");
    }

    @Test
    void spoofedInternalHeadersAreStrippedButOthersPassThrough() {
        as("CUSTOMER").get().uri("/test/echo")
                .header("X-User-Id", "someone-else")
                .header("X-User-Roles", "OPS_ADMIN")
                .header("X-Internal-Token", "secret")
                .header("X-Request-Source", "mobile")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$['x-user-id']").doesNotExist()
                .jsonPath("$['x-user-roles']").doesNotExist()
                .jsonPath("$['x-internal-token']").doesNotExist()
                .jsonPath("$['x-request-source']").isEqualTo("mobile");
    }
}
