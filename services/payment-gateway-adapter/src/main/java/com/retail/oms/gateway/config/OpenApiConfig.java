package com.retail.oms.gateway.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html: only the webhook endpoints are HTTP; the rest is Kafka (Camel routes). */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "payment-gateway-adapter", version = "v1",
        description = "Gateway webhooks (Stripe, PayPal): no JWT, signature checked, de-duplicated by event id. Learning project."))
public class OpenApiConfig {
}
