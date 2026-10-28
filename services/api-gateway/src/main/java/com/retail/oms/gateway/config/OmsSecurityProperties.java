package com.retail.oms.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where tokens come from. {@code issuer} must match the token's {@code iss} claim exactly, which depends on
 * the host name the client used to reach Keycloak (localhost:8180 on a laptop).
 */
@ConfigurationProperties("oms.security")
public record OmsSecurityProperties(String issuer, String jwkSetUri, String audience) {
}
