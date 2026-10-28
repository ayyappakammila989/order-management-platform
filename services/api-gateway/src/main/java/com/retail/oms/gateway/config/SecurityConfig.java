package com.retail.oms.gateway.config;

import com.retail.oms.gateway.security.AudienceValidator;
import com.retail.oms.gateway.security.KeycloakRealmRoleConverter;
import com.retail.oms.gateway.web.GatewayErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The gateway is an OAuth2 resource server: it checks the JWT signature (Keycloak's JWKS, cached by Nimbus),
 * expiry, issuer and audience, then applies the coarse route rules from architecture 6.1 / 7.4.
 * Services behind it validate the token again (zero trust).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    static final String[] EMPLOYEE_ROLES = {"SUPPORT_AGENT", "SUPPORT_SUPERVISOR", "OPS_ADMIN", "FINANCE"};
    static final String[] CHAT_ROLES = {"SUPPORT_AGENT", "SUPPORT_SUPERVISOR", "OPS_ADMIN"};

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder,
                                                  GatewayErrorWriter errors) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(auth -> auth
                        // public: health, circuit-breaker fallback, store locator, webhooks (verified by signature downstream)
                        .pathMatchers("/actuator/**", "/fallback/**", "/webhooks/**", "/api/v1/stores/**").permitAll()
                        .pathMatchers("/api/v1/admin/**").hasAnyRole(EMPLOYEE_ROLES)
                        .pathMatchers("/api/v1/chat/**").hasAnyRole(CHAT_ROLES)
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtDecoder(decoder).jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((exchange, ex) ->
                                errors.write(exchange, HttpStatus.UNAUTHORIZED, "SEC-401", "Authentication required")))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((exchange, ex) ->
                                errors.write(exchange, HttpStatus.UNAUTHORIZED, "SEC-401", "Authentication required"))
                        .accessDeniedHandler((exchange, ex) ->
                                errors.write(exchange, HttpStatus.FORBIDDEN, "SEC-403", "Access denied")))
                .build();
    }

    /** No call to Keycloak at startup: the JWKS is fetched on the first token, so the gateway can start first. */
    @Bean
    ReactiveJwtDecoder jwtDecoder(OmsSecurityProperties props) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
        decoder.setJwtValidator(tokenValidator(props));
        return decoder;
    }

    /** exp/nbf + issuer + audience. */
    public static OAuth2TokenValidator<Jwt> tokenValidator(OmsSecurityProperties props) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuer()),
                new AudienceValidator(props.audience()));
    }

    private Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
