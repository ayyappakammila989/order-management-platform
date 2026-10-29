package com.retail.oms.common.web;

import java.time.Instant;
import java.util.List;
import org.slf4j.MDC;

/** Builds {@link ApiError}s for the advice classes. */
public final class ApiErrorFactory {

    private ApiErrorFactory() {
    }

    public static ApiError build(String path, int status, String errorCode, String message) {
        return build(path, status, errorCode, message, List.of());
    }

    public static ApiError build(String path, int status, String errorCode, String message,
                          List<ApiError.FieldError> fieldErrors) {
        return new ApiError(Instant.now(), status, errorCode, message, path, traceId(), fieldErrors);
    }

    /** The tracing trace id when tracing is on (step 8); until then the correlation id lets support find the logs. */
    private static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : MDC.get(CorrelationId.MDC_KEY);
    }
}
