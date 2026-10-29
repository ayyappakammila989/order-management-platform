package com.retail.oms.cart.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

/** Tests without Keycloak: the service-token manager hands out a fixed fake token. */
@TestConfiguration(proxyBeanMethods = false)
public class ServiceTokenTestConfig {

    public static final String TOKEN = "test-service-token";

    @Bean
    @Primary
    OAuth2AuthorizedClientManager fakeServiceClientManager() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("cart-service")
                .clientId("cart-service").authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://localhost:1/unused").build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, TOKEN,
                Instant.now(), Instant.now().plusSeconds(300));
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any())).thenReturn(new OAuth2AuthorizedClient(registration, "cart-service", token));
        return manager;
    }
}
