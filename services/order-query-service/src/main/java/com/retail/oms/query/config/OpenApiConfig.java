package com.retail.oms.query.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html; "Authorize" takes a Keycloak access token (see docs/local-access.md). */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(title = "order-query-service", version = "v1",
                description = "Order search and lookup (read side). Learning project."),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
