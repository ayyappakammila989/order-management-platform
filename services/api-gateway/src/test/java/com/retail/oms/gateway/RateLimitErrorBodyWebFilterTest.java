package com.retail.oms.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.gateway.web.GatewayErrorWriter;
import com.retail.oms.gateway.web.RateLimitErrorBodyWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** The real RequestRateLimiter needs Redis; here a stand-in filter chain does exactly what it does: 429 + setComplete(). */
class RateLimitErrorBodyWebFilterTest {

    private final RateLimitErrorBodyWebFilter filter =
            new RateLimitErrorBodyWebFilter(new GatewayErrorWriter(new ObjectMapper().findAndRegisterModules()));

    @Test
    void emptyTooManyRequestsResponseGetsTheStandardBody() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/chat/ask"));

        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return ex.getResponse().setComplete();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"status\":429").contains("\"errorCode\":\"SYS-429\"").contains("/api/v1/chat/ask");
    }

    @Test
    void otherResponsesAreLeftAlone() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/web/orders"));

        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return ex.getResponse().setComplete();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange.getResponse().getBodyAsString().defaultIfEmpty("").block()).isEmpty();
    }
}
