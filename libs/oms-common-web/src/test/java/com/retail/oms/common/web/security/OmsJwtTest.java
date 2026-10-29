package com.retail.oms.common.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class OmsJwtTest {

    @Test
    void realmRolesBecomeSpringRolesAndSubIsThePrincipal() {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) OmsJwt.authenticationConverter().convert(
                jwt(Map.of("sub", "u-1", "preferred_username", "agent1",
                        "realm_access", Map.of("roles", List.of("SUPPORT_AGENT", "offline_access")))));

        assertThat(auth.getName()).isEqualTo("u-1");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_SUPPORT_AGENT", "ROLE_offline_access");
        assertThat(OmsJwt.actor(auth)).isEqualTo("agent1");
    }

    @Test
    void serviceAccountActorIsTheClientId() {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) OmsJwt.authenticationConverter().convert(
                jwt(Map.of("sub", "u-2", "preferred_username", "service-account-cart-service", "azp", "cart-service")));

        assertThat(OmsJwt.actor(auth)).isEqualTo("cart-service");
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void audienceMustMatch() {
        AudienceValidator validator = new AudienceValidator("oms-api");

        assertThat(validator.validate(jwt(Map.of("sub", "x", "aud", List.of("oms-api", "account")))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt(Map.of("sub", "x", "aud", List.of("account")))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(Map.of("sub", "x"))).hasErrors()).isTrue();
    }

    @Test
    void entryPointWritesApiErrorBody() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiErrorSecurityHandlers(mapper).authenticationEntryPoint()
                .commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        Map<String, Object> body = mapper.readValue(response.getContentAsByteArray(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        assertThat(body).containsEntry("errorCode", "SEC-401").containsEntry("path", "/api/v1/orders/1");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"), claims);
    }
}
