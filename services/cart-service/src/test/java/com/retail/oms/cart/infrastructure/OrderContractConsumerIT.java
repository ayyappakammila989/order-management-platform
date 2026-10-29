package com.retail.oms.cart.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.retail.oms.cart.application.CartService;
import com.retail.oms.cart.application.CheckoutService;
import com.retail.oms.cart.domain.Cart;
import com.retail.oms.cart.infrastructure.client.OrderClient;
import com.retail.oms.cart.infrastructure.client.OrderClient.OrderRequest;
import com.retail.oms.cart.infrastructure.client.PricingClient;
import com.retail.oms.cart.support.PostgresRedisTestConfig;
import com.retail.oms.cart.support.ServiceTokenTestConfig;
import com.retail.oms.common.web.OmsException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties.StubsMode;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Consumer side of the contract with order-service. Stub Runner starts the real stubs generated from
 * order-service/src/test/resources/contracts (the order-service-*-stubs.jar in the local Maven repository), and the
 * real Feign client, error decoder, Redis stores and checkout logic talk to them over HTTP. If order-service changes
 * the contract in a way cart-service cannot live with, this test fails.
 * Run `mvn -pl services/order-service install` first so the current stubs are in ~/.m2.
 */
@SpringBootTest(properties = "oms.order-service.url=http://localhost:8582")
@AutoConfigureStubRunner(ids = "com.retail.oms:order-service:+:stubs:8582", stubsMode = StubsMode.LOCAL)
@Import({PostgresRedisTestConfig.class, ServiceTokenTestConfig.class})
class OrderContractConsumerIT {

    @Autowired CheckoutService checkout;
    @Autowired CartService carts;
    @Autowired OrderClient orderClient;
    @MockitoBean PricingClient pricing;

    @Test
    void checkoutRequestMatchesTheContractAndTheOrderComesBack() {
        Cart cart = carts.create("cust-1", "USD");
        cart = carts.addItem(cart.cartId(), "cust-1", "SKU-1", 2);
        String cartId = cart.cartId();
        when(pricing.quote(any())).thenReturn(new PricingClient.Quote("USD", List.of(new PricingClient.Quote.QuoteLine(
                "SKU-1", 2, new BigDecimal("19.99"), new BigDecimal("2.00"), new BigDecimal("3.00"))),
                new BigDecimal("39.98"), new BigDecimal("2.00"), new BigDecimal("3.00"), new BigDecimal("4.99"),
                new BigDecimal("45.97")));

        CheckoutService.Result result = checkout.checkout(new CheckoutService.Command(cartId, "cust-1",
                "checkout-7f3c2a10", "WEB", null,
                new OrderRequest.Address("Jane Doe", "1 Main St", "New York", "NY", "10001")));

        assertThat(result.order().orderNumber()).matches("OMS-[0-9]{8}-[0-9]{6}");
        assertThat(result.order().status()).isEqualTo("PENDING");
        assertThat(result.order().grandTotal()).isEqualByComparingTo("45.97");
        // the cart was deleted after the order was created
        assertThatThrownBy(() -> carts.get(cartId, "cust-1")).isInstanceOf(OmsException.class);
    }

    @Test
    void totalMismatchFromOrderServiceReachesTheCallerWithItsErrorCode() {
        Cart cart = carts.create("cust-1", "USD");
        cart = carts.addItem(cart.cartId(), "cust-1", "SKU-1", 1);
        String cartId = cart.cartId();
        // a (wrong) quote whose total does not equal the line: the shape of the "mismatch" contract
        when(pricing.quote(any())).thenReturn(new PricingClient.Quote("USD", List.of(new PricingClient.Quote.QuoteLine(
                "SKU-1", 1, new BigDecimal("5.00"), null, null)),
                new BigDecimal("5.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.00"), new BigDecimal("4.00")));

        assertThatThrownBy(() -> checkout.checkout(new CheckoutService.Command(cartId, "cust-1", "checkout-mismatch-01",
                "WEB", null, new OrderRequest.Address("Jane Doe", "1 Main St", "New York", "NY", "10001"))))
                .isInstanceOfSatisfying(OmsException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("ORD-422");
                    assertThat(e.getHttpStatus().value()).isEqualTo(422);
                });
        assertThat(carts.get(cartId, "cust-1").items()).hasSize(1);      // the cart is kept so the customer can retry
    }

    @Test
    void missingIdempotencyKeyIsRefusedByOrderService() {
        // CheckoutService always sends a key, so this calls the client directly (the shape of the "no key" contract)
        OrderRequest request = new OrderRequest("cust-no-key", "WEB", "USD", List.of(new OrderRequest.Line("SKU-1", 1,
                new BigDecimal("5.00"), null, null, "SHIP")),
                new OrderRequest.Address("Jane Doe", "1 Main St", "New York", "NY", "10001"),
                new BigDecimal("0.00"), null);

        assertThatThrownBy(() -> orderClient.create(null, request))
                .isInstanceOf(NoFallbackAvailableException.class)      // the circuit breaker wraps what the decoder threw
                .cause().isInstanceOfSatisfying(OmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("IDM-400"));
    }
}
