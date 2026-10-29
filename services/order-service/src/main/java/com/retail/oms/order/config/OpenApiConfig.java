package com.retail.oms.order.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html; "Authorize" takes a Keycloak access token (see docs/local-access.md). */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(title = "order-service", version = "v1",
                description = "Owns orders: create (idempotent), read, cancel, saga status updates. Learning project."),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
