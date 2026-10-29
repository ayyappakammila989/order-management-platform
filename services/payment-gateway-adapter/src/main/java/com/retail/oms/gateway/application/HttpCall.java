package com.retail.oms.gateway.application;

import java.util.Map;

/** One HTTP request to a gateway, built from a GatewayCommand; the Camel route sends it with the http component. */
public record HttpCall(String method, String url, Map<String, String> headers, String body) {
}
