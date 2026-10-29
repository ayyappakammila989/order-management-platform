package com.retail.oms.common.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.common.web.ApiError;
import com.retail.oms.common.web.ApiErrorFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 and 403 raised inside the security filter chain never reach @RestControllerAdvice, so these write the
 * same {@link ApiError} body directly. The WWW-Authenticate header still tells clients why a token was refused.
 */
@Slf4j
public final class ApiErrorSecurityHandlers {

    private final ObjectMapper objectMapper;

    public ApiErrorSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> {
            log.warn("401 on {}: {}", request.getRequestURI(), ex.getMessage());
            response.setHeader("WWW-Authenticate", "Bearer");
            write(request, response, 401, "SEC-401", "Authentication required");
        };
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> {
            log.warn("403 on {}: {}", request.getRequestURI(), ex.getMessage());
            write(request, response, 403, "SEC-403", "Access denied");
        };
    }

    private void write(HttpServletRequest request, HttpServletResponse response, int status, String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiErrorFactory.build(request.getRequestURI(), status, code, message));
    }
}
