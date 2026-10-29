package com.retail.oms.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.retail.oms.cart.application.CheckoutRecordStore.CheckoutRecord;
import com.retail.oms.cart.domain.Cart;
import com.retail.oms.cart.infrastructure.client.OrderClient;
import com.retail.oms.cart.infrastructure.client.OrderClient.OrderRequest;
import com.retail.oms.cart.infrastructure.client.OrderClient.OrderSummary;
import com.retail.oms.cart.infrastructure.client.PricingClient;
import com.retail.oms.common.web.OmsException;
import feign.FeignException;
import feign.Request;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    static final String KEY = "checkout-7f3c2a10";
    static final OrderRequest.Address ADDRESS = new OrderRequest.Address("Jane Doe", "1 Main St", "New York", "NY", "10001");

    @Mock CartService carts;
    @Mock PricingClient pricing;
    @Mock OrderClient orders;
    InMemoryRecords records = new InMemoryRecords();
    CheckoutService service;
    Cart cart;

    @BeforeEach
    void setUp() {
        service = new CheckoutService(carts, pricing, orders, records);
        cart = Cart.empty("cust-1", "USD", Instant.parse("2026-10-29T10:00:00Z")).addItem("SKU-1", 2, Instant.now());
    }

    CheckoutService.Command command() {
        return new CheckoutService.Command(cart.cartId(), "cust-1", KEY, "WEB", "SPRING10", ADDRESS);
    }

    PricingClient.Quote quote(String grand) {
        return new PricingClient.Quote("USD", List.of(new PricingClient.Quote.QuoteLine("SKU-1", 2,
                new BigDecimal("19.99"), new BigDecimal("2.00"), new BigDecimal("3.00"))),
                new BigDecimal("39.98"), new BigDecimal("2.00"), new BigDecimal("3.00"), new BigDecimal("4.99"),
                new BigDecimal(grand));
    }

    OrderSummary order() {
        return new OrderSummary(UUID.fromString("9f3c6a2e-5b1d-4c8e-9a7f-2e6d1c0b4a3f"), "OMS-20261029-000001", "cust-1",
                "PENDING", "USD", new BigDecimal("39.98"), new BigDecimal("45.97"));
    }

    @Test
    void happyPathSendsThePricedLinesWithTheIdempotencyKeyAndDeletesTheCart() {
        when(carts.get(cart.cartId(), "cust-1")).thenReturn(cart);
        when(pricing.quote(any())).thenReturn(quote("45.97"));
        when(orders.create(eq(KEY), any())).thenReturn(ResponseEntity.status(201).body(order()));

        CheckoutService.Result result = service.checkout(command());

        assertThat(result.order().orderNumber()).isEqualTo("OMS-20261029-000001");
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<PricingClient.QuoteRequest> quoteRequest = ArgumentCaptor.forClass(PricingClient.QuoteRequest.class);
        verify(pricing).quote(quoteRequest.capture());
        assertThat(quoteRequest.getValue().shipToZip()).isEqualTo("10001");
        assertThat(quoteRequest.getValue().couponCode()).isEqualTo("SPRING10");

        ArgumentCaptor<OrderRequest> orderRequest = ArgumentCaptor.forClass(OrderRequest.class);
        verify(orders).create(eq(KEY), orderRequest.capture());
        OrderRequest sent = orderRequest.getValue();
        assertThat(sent.customerId()).isEqualTo("cust-1");
        assertThat(sent.expectedGrandTotal()).isEqualByComparingTo("45.97");
        assertThat(sent.shippingTotal()).isEqualByComparingTo("4.99");
        assertThat(sent.lines()).singleElement().satisfies(l -> {
            assertThat(l.discount()).isEqualByComparingTo("2.00");
            assertThat(l.tax()).isEqualByComparingTo("3.00");
            assertThat(l.fulfillmentType()).isEqualTo("SHIP");
        });
        verify(carts).delete(cart.cartId());
        assertThat(records.map.get("cust-1:" + KEY).order()).isNotNull();
    }

    @Test
    void emptyCartIsRefusedBeforeAnyCall() {
        Cart empty = Cart.empty("cust-1", "USD", Instant.now());
        when(carts.get(empty.cartId(), "cust-1")).thenReturn(empty);

        assertThatThrownBy(() -> service.checkout(new CheckoutService.Command(empty.cartId(), "cust-1", KEY, "WEB", null, ADDRESS)))
                .isInstanceOf(OmsException.class).hasMessage("The cart is empty");
        verifyNoInteractions(pricing, orders);
    }

    @Test
    void pricingRejectionPassesThroughAndNothingIsRecordedOrCreated() {
        when(carts.get(cart.cartId(), "cust-1")).thenReturn(cart);
        when(pricing.quote(any())).thenThrow(new OmsException("PRC-422", HttpStatus.UNPROCESSABLE_ENTITY, "Coupon SPRING10 is not valid"));

        assertThatThrownBy(() -> service.checkout(command()))
                .isInstanceOfSatisfying(OmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("PRC-422"));
        verifyNoInteractions(orders);
        verify(carts, never()).delete(any());
        assertThat(records.map).isEmpty();      // the customer can fix the coupon and retry with the same key
    }

    @Test
    void retryAfterSuccessReturnsTheSameOrderWithoutPricingOrCallingOrderService() {
        records.map.put("cust-1:" + KEY, new CheckoutRecord(cart.cartId(), null, order()));

        CheckoutService.Result result = service.checkout(command());

        assertThat(result.replayed()).isTrue();
        assertThat(result.order().orderId()).isEqualTo(order().orderId());
        verifyNoInteractions(pricing, orders, carts);      // the cart is already gone; that must not matter
    }

    @Test
    void retryAfterAnInterruptedAttemptReusesTheFrozenRequestEvenIfPricesMoved() {
        OrderRequest frozen = new OrderRequest("cust-1", "WEB", "USD", List.of(), ADDRESS, new BigDecimal("4.99"), new BigDecimal("45.97"));
        records.map.put("cust-1:" + KEY, new CheckoutRecord(cart.cartId(), frozen, null));
        when(orders.create(eq(KEY), any())).thenReturn(ResponseEntity.status(201)
                .header(OrderClient.REPLAYED, "true").body(order()));

        CheckoutService.Result result = service.checkout(command());

        verifyNoInteractions(pricing);
        verify(orders).create(KEY, frozen);
        assertThat(result.replayed()).isTrue();      // order-service said it had seen the key
        verify(carts).delete(cart.cartId());
    }

    @Test
    void sameKeyForAnotherCartIsRefused() {
        records.map.put("cust-1:" + KEY, new CheckoutRecord("some-other-cart", null, order()));

        assertThatThrownBy(() -> service.checkout(command()))
                .isInstanceOfSatisfying(OmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("IDM-422"));
        verifyNoInteractions(pricing, orders);
    }

    @Test
    void orderServiceDownKeepsTheCartAndTheFrozenRequestForTheRetry() {
        when(carts.get(cart.cartId(), "cust-1")).thenReturn(cart);
        when(pricing.quote(any())).thenReturn(quote("45.97"));
        Request request = Request.create(Request.HttpMethod.POST, "http://order-service/internal/v1/orders", Map.of(), null, null, null);
        when(orders.create(eq(KEY), any())).thenThrow(new feign.RetryableException(-1, "connection refused",
                Request.HttpMethod.POST, (Long) null, request));

        assertThatThrownBy(() -> service.checkout(command()))
                .isInstanceOfSatisfying(OmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("CRT-503"));

        verify(carts, never()).delete(any());
        assertThat(records.map.get("cust-1:" + KEY).order()).isNull();
        assertThat(records.map.get("cust-1:" + KEY).request()).isNotNull();

        // the retry does not price again
        when(orders.create(eq(KEY), any())).thenReturn(ResponseEntity.status(201).body(order()));
        service.checkout(command());
        verify(pricing, times(1)).quote(any());
        verify(carts).delete(cart.cartId());
    }

    @Test
    void feignFailuresOfAnyKindBecome503() {
        when(carts.get(cart.cartId(), "cust-1")).thenReturn(cart);
        when(pricing.quote(any())).thenThrow(FeignException.errorStatus("quote", feign.Response.builder().status(500)
                .request(Request.create(Request.HttpMethod.POST, "http://x", Map.of(), null, null, null))
                .headers(Map.of()).build()));

        assertThatThrownBy(() -> service.checkout(command()))
                .isInstanceOfSatisfying(OmsException.class, e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void rejectionWrappedByTheCircuitBreakerIsUnwrapped() {
        when(carts.get(cart.cartId(), "cust-1")).thenReturn(cart);
        when(pricing.quote(any())).thenThrow(new NoFallbackAvailableException("No fallback available.",
                new OmsException("PRC-404", HttpStatus.NOT_FOUND, "Unknown SKU: SKU-1")));

        assertThatThrownBy(() -> service.checkout(command()))
                .isInstanceOfSatisfying(OmsException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("PRC-404");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    void technicalFailureWrappedByTheCircuitBreakerBecomes503() {
        when(carts.get(cart.cartId(), "cust-1")).thenReturn(cart);
        when(pricing.quote(any())).thenThrow(new NoFallbackAvailableException("No fallback available.",
                new java.net.ConnectException("refused")));

        assertThatThrownBy(() -> service.checkout(command()))
                .isInstanceOfSatisfying(OmsException.class, e -> assertThat(e.getErrorCode()).isEqualTo("CRT-503"));
    }

    static class InMemoryRecords implements CheckoutRecordStore {
        final Map<String, CheckoutRecord> map = new HashMap<>();

        @Override
        public Optional<CheckoutRecord> find(String customerId, String key) {
            return Optional.ofNullable(map.get(customerId + ":" + key));
        }

        @Override
        public boolean saveRequest(String customerId, String key, String cartId, OrderRequest request) {
            return map.putIfAbsent(customerId + ":" + key, new CheckoutRecord(cartId, request, null)) == null;
        }

        @Override
        public void saveOrder(String customerId, String key, CheckoutRecord record) {
            map.put(customerId + ":" + key, record);
        }
    }
}
