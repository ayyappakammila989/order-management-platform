package com.retail.oms.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.retail.oms.gateway.config.OmsSecurityProperties;
import com.retail.oms.gateway.config.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class TokenValidationTest {

    private static final OmsSecurityProperties PROPS =
            new OmsSecurityProperties("http://localhost:8180/realms/oms", "unused", "oms-api");
    private final OAuth2TokenValidator<Jwt> validator = SecurityConfig.tokenValidator(PROPS);

    private static Jwt jwt(String issuer, List<String> audience, Instant expiresAt) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer(issuer)
                .audience(audience)
                .subject("user-1")
                .issuedAt(expiresAt.minusSeconds(300))
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    void acceptsTokenWithRightIssuerAudienceAndExpiry() {
        Jwt ok = jwt("http://localhost:8180/realms/oms", List.of("account", "oms-api"), Instant.now().plusSeconds(300));
        assertThat(validator.validate(ok).hasErrors()).isFalse();
    }

    @Test
    void rejectsWrongIssuer() {
        Jwt bad = jwt("http://evil.example/realms/oms", List.of("oms-api"), Instant.now().plusSeconds(300));
        assertThat(validator.validate(bad).hasErrors()).isTrue();
    }

    @Test
    void rejectsWrongAudience() {
        Jwt bad = jwt("http://localhost:8180/realms/oms", List.of("account"), Instant.now().plusSeconds(300));
        assertThat(validator.validate(bad).hasErrors()).isTrue();
    }

    @Test
    void rejectsExpiredToken() {
        Jwt bad = jwt("http://localhost:8180/realms/oms", List.of("oms-api"), Instant.now().minusSeconds(120));
        assertThat(validator.validate(bad).hasErrors()).isTrue();
    }

    @Test
    void mapsRealmRolesToAuthorities() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .claim("realm_access", Map.of("roles", List.of("SUPPORT_AGENT", "offline_access")))
                .build();
        assertThat(new KeycloakRealmRoleConverter().convert(token))
                .extracting(Object::toString)
                .containsExactly("ROLE_SUPPORT_AGENT", "ROLE_offline_access");
    }

    @Test
    void noRealmAccessClaimMeansNoAuthorities() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "RS256").subject("u").claim("scope", "openid").build();
        assertThat(new KeycloakRealmRoleConverter().convert(token)).isEmpty();
    }
}
