package com.retail.oms.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception into {@link ApiError}. Stack traces go to the log, never to the client.
 * Extends Spring's {@link ResponseEntityExceptionHandler}, which already maps the standard MVC exceptions
 * (404, 405, 415, malformed JSON, type mismatch ...); we only change the body they are written with.
 * Runs last so the more specific advice beans (security, Resilience4j) get the first chance.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(OmsException.class)
    ResponseEntity<ApiError> handleOms(OmsException ex, HttpServletRequest request) {
        if (ex.getHttpStatus().is5xxServerError()) {
            log.error("{} {}", ex.getErrorCode(), ex.getMessage(), ex);
        } else {
            log.warn("{} {}", ex.getErrorCode(), ex.getMessage());
        }
        return respond(ApiErrorFactory.build(request.getRequestURI(), ex.getHttpStatus().value(),
                ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return validationFailed(request.getRequestURI(), fields);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleAny(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return respond(ApiErrorFactory.build(request.getRequestURI(), 500, "SYS-500", "Unexpected error"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getAllErrors().stream()
                .map(e -> new ApiError.FieldError(
                        e instanceof FieldError fe ? fe.getField() : e.getObjectName(), e.getDefaultMessage()))
                .toList();
        return asObject(validationFailed(path(request), fields));
    }

    /** @RequestParam / @PathVariable constraints (Spring 6.1+ built-in method validation). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiError.FieldError> fields = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> new ApiError.FieldError(r.getMethodParameter().getParameterName(),
                                e.getDefaultMessage())))
                .toList();
        return asObject(validationFailed(path(request), fields));
    }

    /** Every other standard MVC exception (404, 405, 415, malformed body ...) ends up here. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("{} on {}", status.value(), path(request), ex);
        } else {
            log.warn("{} on {}: {}", status.value(), path(request), ex.getMessage());
        }
        ApiError error = status.value() == 400
                ? ApiErrorFactory.build(path(request), 400, "VAL-400", "Malformed request")
                : ApiErrorFactory.build(path(request), status.value(), "SYS-" + status.value(), reason(status));
        return ResponseEntity.status(status).headers(headers).body(error);
    }

    private static ResponseEntity<ApiError> validationFailed(String path, List<ApiError.FieldError> fields) {
        return respond(ApiErrorFactory.build(path, 400, "VAL-400", "Validation failed", fields));
    }

    private static ResponseEntity<Object> asObject(ResponseEntity<ApiError> entity) {
        return ResponseEntity.status(entity.getStatusCode()).body(entity.getBody());
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "";
    }

    private static String reason(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "Request failed";
    }

    static ResponseEntity<ApiError> respond(ApiError body) {
        return ResponseEntity.status(body.status()).body(body);
    }
}
