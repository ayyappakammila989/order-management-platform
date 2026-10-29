package com.retail.oms.cart.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.retail.oms.cart.application.CartService;
import com.retail.oms.cart.application.CheckoutService;
import com.retail.oms.cart.application.SavedCartService;
import com.retail.oms.cart.config.SecurityConfig;
import com.retail.oms.cart.domain.Cart;
import com.retail.oms.cart.exception.CartNotFoundException;
import com.retail.oms.cart.infrastructure.client.OrderClient.OrderSummary;
import com.retail.oms.common.web.OmsCommonWebAutoConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = {CartController.class, SavedCartController.class})
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OmsCommonWebAutoConfiguration.class)
class CartApiTest {

    static final String CHECKOUT_BODY = """
            {"shippingAddress":{"name":"Jane Doe","line1":"1 Main St","city":"New York","state":"NY","zip":"10001"},
             "couponCode":"SPRING10"}""";

    @Autowired MockMvc mvc;
    @MockitoBean CartService carts;
    @MockitoBean CheckoutService checkout;
    @MockitoBean SavedCartService savedCarts;

    static RequestPostProcessor customer(String id) {
        return jwt().jwt(j -> j.subject(id)).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Test
    void everythingNeedsAToken() throws Exception {
        mvc.perform(post("/api/v1/carts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/carts/abc")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/carts/abc/checkout").header("Idempotency-Key", "checkout-0001")
                .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(carts, checkout);
    }

    @Test
    void createTakesTheCustomerFromTheTokenAndReturns201WithLocation() throws Exception {
        Cart cart = Cart.empty("cust-1", "USD", Instant.parse("2026-10-29T10:00:00Z"));
        when(carts.create("cust-1", null)).thenReturn(cart);

        mvc.perform(post("/api/v1/carts").with(customer("cust-1")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/carts/" + cart.cartId()))
                .andExpect(jsonPath("$.customerId").value("cust-1"))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void addItemValidatesTheBody() throws Exception {
        mvc.perform(post("/api/v1/carts/c1/items").with(customer("cust-1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VAL-400"));
        verifyNoInteractions(carts);
    }

    @Test
    void addChangeAndRemoveUseTheCallerAsOwner() throws Exception {
        Cart cart = Cart.empty("cust-1", "USD", Instant.now()).addItem("SKU-1", 2, Instant.now());
        String itemId = cart.items().get(0).itemId();
        when(carts.addItem("c1", "cust-1", "SKU-1", 2)).thenReturn(cart);
        when(carts.changeQuantity("c1", "cust-1", itemId, 4)).thenReturn(cart);
        when(carts.removeItem("c1", "cust-1", itemId)).thenReturn(cart);

        mvc.perform(post("/api/v1/carts/c1/items").with(customer("cust-1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"SKU-1\",\"quantity\":2}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sku").value("SKU-1"));
        mvc.perform(put("/api/v1/carts/c1/items/" + itemId).with(customer("cust-1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":4}")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/carts/c1/items/" + itemId).with(customer("cust-1"))).andExpect(status().isOk());
    }

    @Test
    void someoneElsesCartIs404() throws Exception {
        when(carts.get("c1", "cust-2")).thenThrow(new CartNotFoundException("c1"));

        mvc.perform(get("/api/v1/carts/c1").with(customer("cust-2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CRT-404"));
    }

    @Test
    void checkoutWithoutIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/api/v1/carts/c1/checkout").with(customer("cust-1")).contentType(MediaType.APPLICATION_JSON)
                        .content(CHECKOUT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDM-400"));
        verifyNoInteractions(checkout);
    }

    @Test
    void checkoutWithBadZipIs400() throws Exception {
        mvc.perform(post("/api/v1/carts/c1/checkout").with(customer("cust-1")).header("Idempotency-Key", "checkout-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":{\"name\":\"J\",\"line1\":\"1\",\"city\":\"NY\",\"state\":\"NY\",\"zip\":\"abc\"}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(checkout);
    }

    @Test
    void checkoutReturnsTheOrderAndReportsReplays() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderSummary order = new OrderSummary(orderId, "OMS-20261029-000001", "cust-1", "PENDING", "USD",
                new BigDecimal("39.98"), new BigDecimal("45.97"));
        when(checkout.checkout(any())).thenReturn(new CheckoutService.Result(order, true));

        mvc.perform(post("/api/v1/carts/c1/checkout").with(customer("cust-1")).header("Idempotency-Key", "checkout-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", "/api/v1/orders/" + orderId))
                .andExpect(jsonPath("$.orderNumber").value("OMS-20261029-000001"))
                .andExpect(jsonPath("$.grandTotal").value(45.97));

        org.mockito.ArgumentCaptor<CheckoutService.Command> captor = org.mockito.ArgumentCaptor.forClass(CheckoutService.Command.class);
        verify(checkout).checkout(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().customerId()).isEqualTo("cust-1");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().channel()).isEqualTo("WEB");      // default
        org.assertj.core.api.Assertions.assertThat(captor.getValue().couponCode()).isEqualTo("SPRING10");
    }
}
