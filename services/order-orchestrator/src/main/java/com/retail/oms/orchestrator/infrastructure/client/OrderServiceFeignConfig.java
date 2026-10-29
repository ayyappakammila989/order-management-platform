package com.retail.oms.orchestrator.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.common.web.CorrelationId;
import com.retail.oms.orchestrator.application.OrderRejectedException;
import com.retail.oms.orchestrator.application.OrderServiceUnavailableException;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;

/**
 * Calls to order-service carry a client-credentials token for "order-orchestrator" (role SERVICE) and the
 * correlation id. A 4xx with an error code is a business answer ({@link OrderRejectedException}); everything else,
 * including our own token being refused, is technical ({@link OrderServiceUnavailableException}) and retried.
 * (Not a @Configuration on purpose: only this Feign client uses it.)
 */
public class OrderServiceFeignConfig {

    static final String REGISTRATION = "order-orchestrator";
    private static final Set<Integer> BUSINESS = Set.of(400, 404, 409, 422);

    @Bean
    OAuth2AuthorizedClientManager orchestratorClientManager(ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService clients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(new ClientCredentialsOAuth2AuthorizedClientProvider());
        return manager;
    }

    @Bean
    RequestInterceptor serviceTokenInterceptor(OAuth2AuthorizedClientManager orchestratorClientManager) {
        return template -> {
            try {
                var client = orchestratorClientManager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION)
                        .principal(REGISTRATION).build());
                template.header(HttpHeaders.AUTHORIZATION, "Bearer " + client.getAccessToken().getTokenValue());
            } catch (OAuth2AuthorizationException | IllegalStateException e) {
                throw new OrderServiceUnavailableException("Cannot get a service token (is Keycloak reachable?)", e);
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

    @Bean
    ErrorDecoder orderServiceErrorDecoder(ObjectMapper objectMapper) {
        return (methodKey, response) -> {
            if (BUSINESS.contains(response.status()) && response.body() != null) {
                try (var body = response.body().asInputStream()) {
                    JsonNode json = objectMapper.readTree(body);
                    if (json.hasNonNull("errorCode")) {
                        return new OrderRejectedException(json.get("errorCode").asText(), json.path("message").asText());
                    }
                } catch (IOException e) {
                    // unreadable body: treat as technical below
                }
            }
            return new OrderServiceUnavailableException("order-service answered HTTP " + response.status(), null);
        };
    }
}
