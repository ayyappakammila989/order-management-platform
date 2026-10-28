package com.retail.oms.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** The one error body every service returns (architecture section 14.4). */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String errorCode,
        String message,
        String path,
        String traceId,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(int status, String errorCode, String message, String path, String traceId) {
        return new ApiError(Instant.now(), status, errorCode, message, path, traceId, List.of());
    }
}
