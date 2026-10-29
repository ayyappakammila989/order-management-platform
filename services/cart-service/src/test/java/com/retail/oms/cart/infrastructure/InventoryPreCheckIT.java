package com.retail.oms.cart.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.retail.oms.cart.application.CartService;
import com.retail.oms.cart.application.InventoryPreCheck;
import com.retail.oms.cart.domain.Cart;
import com.retail.oms.cart.exception.OutOfStockException;
import com.retail.oms.cart.support.PostgresRedisTestConfig;
import com.retail.oms.cart.support.ServiceTokenTestConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The pre-check with the real Feign client, circuit breaker, timeouts and retry against an HTTP inventory-service
 * (WireMock). Shows what a unit test cannot: the token and ZIP really go out, a slow answer is cut off after 1 s,
 * and an unavailable inventory-service does not block checkout. (WireMock 2.35 from the contract Stub Runner: a second,
 * newer WireMock on the classpath breaks Stub Runner.)
 */
@SpringBootTest
@AutoConfigureObservability
@Import({PostgresRedisTestConfig.class, ServiceTokenTestConfig.class})
class InventoryPreCheckIT {

    static final WireMockServer INVENTORY = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        INVENTORY.start();
    }

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry registry) {
        registry.add("oms.inventory-service.url", INVENTORY::baseUrl);
    }

    @AfterAll
    static void stop() {
        INVENTORY.stop();
    }

    @Autowired InventoryPreCheck preCheck;
    @Autowired CartService carts;
    @Autowired MeterRegistry meters;

    Cart cart;

    @BeforeEach
    void cart() {
        INVENTORY.resetAll();
        cart = carts.addItem(carts.create("cust-it", "USD").cartId(), "cust-it", "SKU-5", 3);
    }

    static void available(int n) {
        INVENTORY.stubFor(get(urlPathEqualTo("/api/v1/inventory/SKU-5/availability")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"sku\":\"SKU-5\",\"zip\":\"10001\",\"available\":" + n + ",\"locations\":[]}")));
    }

    double skipped() {
        return meters.counter("oms.cart.inventory_check.skipped").count();
    }

    @Test
    void enoughStockPassesAndTheCallCarriesTheServiceTokenAndZip() {
        available(5);

        preCheck.check(cart, "10001");

        INVENTORY.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/inventory/SKU-5/availability"))
                .withQueryParam("zip", equalTo("10001"))
                .withHeader("Authorization", equalTo("Bearer " + ServiceTokenTestConfig.TOKEN)));
    }

    @Test
    void notEnoughStockIsRefused() {
        available(2);

        assertThatThrownBy(() -> preCheck.check(cart, "10001")).isInstanceOf(OutOfStockException.class)
                .hasMessage("Not enough stock: SKU-5 (requested 3, available 2)");
    }

    @Test
    void twoFailuresAreRetriedAndTheThirdAnswerCounts() {
        var path = urlPathEqualTo("/api/v1/inventory/SKU-5/availability");
        INVENTORY.stubFor(get(path).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("again"));
        INVENTORY.stubFor(get(path).inScenario("flaky").whenScenarioStateIs("again")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        INVENTORY.stubFor(get(path).inScenario("flaky").whenScenarioStateIs("ok").willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"sku\":\"SKU-5\",\"available\":1}")));

        assertThatThrownBy(() -> preCheck.check(cart, "10001")).isInstanceOf(OutOfStockException.class);   // answer used
        INVENTORY.verify(3, getRequestedFor(path));
    }

    @Test
    void slowInventoryIsCutOffAndCheckoutIsAllowed() {
        INVENTORY.stubFor(get(urlPathEqualTo("/api/v1/inventory/SKU-5/availability")).willReturn(aResponse()
                .withFixedDelay(2_000).withHeader("Content-Type", "application/json").withBody("{\"available\":0}")));
        double before = skipped();
        long start = System.nanoTime();

        preCheck.check(cart, "10001");                       // no exception: the saga will re-check the stock

        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertThat(took).isBetween(Duration.ofMillis(2_900), Duration.ofMillis(5_500));   // 3 x 1 s read timeout + waits
        assertThat(skipped() - before).isEqualTo(1);
    }

    @Test
    void inventoryDownIsAllowedQuickly() {
        INVENTORY.stubFor(get(urlPathEqualTo("/api/v1/inventory/SKU-5/availability")).willReturn(aResponse().withStatus(500)));
        double before = skipped();

        preCheck.check(cart, "10001");

        assertThat(skipped() - before).isEqualTo(1);
        INVENTORY.verify(3, getRequestedFor(urlPathEqualTo("/api/v1/inventory/SKU-5/availability")));
    }
}
