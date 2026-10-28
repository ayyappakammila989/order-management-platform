package com.retail.oms.common.web;

/** Names shared by the servlet filter here and the reactive filter in api-gateway. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }
}
