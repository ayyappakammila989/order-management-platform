package com.retail.oms.common.web.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Building blocks every servlet resource server uses the same way (zero trust: each service validates the token
 * again, architecture 7.2).
 */
public final class OmsJwt {

    /** Signature via the issuer's JWKS, plus exp/nbf, issuer and audience. */
    public static JwtDecoder decoder(String issuer, String jwkSetUri, String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new AudienceValidator(audience)));
        return decoder;
    }

    /** Principal name = {@code sub} (the Keycloak user id), authorities = realm roles. */
    public static JwtAuthenticationConverter authenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    /**
     * Readable name for audit columns: the login name for people, the client id for services
     * (client-credentials tokens carry {@code azp}), else the subject.
     */
    public static String actor(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            String username = token.getToken().getClaimAsString("preferred_username");
            if (username != null && !username.startsWith("service-account-")) {
                return username;
            }
            String clientId = token.getToken().getClaimAsString("azp");
            if (clientId != null) {
                return clientId;
            }
        }
        return authentication.getName();
    }

    private OmsJwt() {
    }
}
