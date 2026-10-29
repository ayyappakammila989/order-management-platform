package com.retail.oms.cart.config;

import com.retail.oms.common.web.CorrelationId;
import com.retail.oms.common.web.OmsException;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;

/**
 * Every outgoing call to another service carries (1) a client-credentials token for "cart-service" (role SERVICE,
 * cached and refreshed by Spring Security) and (2) the correlation id of the request being served.
 */
@Configuration(proxyBeanMethods = false)
public class FeignSecurityConfig {

    static final String REGISTRATION = "cart-service";

    @Bean
    OAuth2AuthorizedClientManager serviceClientManager(ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService clients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(new ClientCredentialsOAuth2AuthorizedClientProvider());
        return manager;
    }

    @Bean
    RequestInterceptor serviceTokenInterceptor(OAuth2AuthorizedClientManager manager) {
        return template -> {
            try {
                var client = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION)
                        .principal(REGISTRATION).build());
                template.header(HttpHeaders.AUTHORIZATION, "Bearer " + client.getAccessToken().getTokenValue());
            } catch (OAuth2AuthorizationException e) {
                throw new OmsException("CRT-503", HttpStatus.SERVICE_UNAVAILABLE,
                        "Cannot get a service token (is Keycloak reachable?)");
            }
        };
    }

    @Bean
    RequestInterceptor correlationInterceptor() {
        return template -> {
            String id = MDC.get(CorrelationId.MDC_KEY);
            if (id != null) {
                template.header(CorrelationId.HEADER, id);
            }
        };
    }
}
