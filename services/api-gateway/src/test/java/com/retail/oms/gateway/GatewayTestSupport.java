package com.retail.oms.gateway;

import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Test-only downstream: a route that forwards to an in-process controller echoing the headers it received. */
@TestConfiguration
class GatewayTestSupport {

    @Bean
    RouteLocator echoRoute(RouteLocatorBuilder builder) {
        return builder.routes().route("echo", r -> r.path("/test/echo").uri("forward:/echo")).build();
    }

    @RestController
    static class EchoController {
        @GetMapping("/echo")
        Map<String, String> echo(@RequestHeader HttpHeaders headers) {
            Map<String, String> seen = new TreeMap<>();
            headers.forEach((k, v) -> seen.put(k.toLowerCase(), String.join(",", v)));
            return seen;
        }
    }
}
