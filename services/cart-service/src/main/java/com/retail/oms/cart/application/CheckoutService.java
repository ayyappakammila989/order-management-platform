package com.retail.oms.cart.application;

import com.retail.oms.cart.application.CheckoutRecordStore.CheckoutRecord;
import com.retail.oms.cart.domain.Cart;
import com.retail.oms.cart.exception.CartRuleException;
import com.retail.oms.cart.infrastructure.client.OrderClient;
import com.retail.oms.cart.infrastructure.client.OrderClient.OrderRequest;
import com.retail.oms.cart.infrastructure.client.OrderClient.OrderSummary;
import com.retail.oms.cart.infrastructure.client.PricingClient;
import com.retail.oms.common.web.OmsException;
import feign.FeignException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Architecture 3.1 steps 3 and 5: price the cart with pricing-service, then create the order in order-service with
 * the client's Idempotency-Key. (Step 4, the inventory pre-check, comes with inventory-service in step 3.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutService {

    public record Command(String cartId, String customerId, String idempotencyKey, String channel, String couponCode,
                          OrderRequest.Address shippingAddress) {
    }

    public record Result(OrderSummary order, boolean replayed) {
    }

    private final CartService carts;
    private final PricingClient pricing;
    private final OrderClient orders;
    private final CheckoutRecordStore records;

    public Result checkout(Command cmd) {
        Optional<CheckoutRecord> previous = records.find(cmd.customerId(), cmd.idempotencyKey());
        if (previous.isPresent()) {
            return resume(cmd, previous.get());
        }

        Cart cart = carts.get(cmd.cartId(), cmd.customerId());
        if (cart.items().isEmpty()) {
            throw new CartRuleException("The cart is empty");
        }
        OrderRequest request = priced(cmd, cart);
        if (!records.saveRequest(cmd.customerId(), cmd.idempotencyKey(), cmd.cartId(), request)) {
            // another request with this key got in between: join it
            return resume(cmd, records.find(cmd.customerId(), cmd.idempotencyKey()).orElseThrow());
        }
        return place(cmd, request);
    }

    private Result resume(Command cmd, CheckoutRecord record) {
        if (!record.cartId().equals(cmd.cartId())) {
            throw new OmsException("IDM-422", HttpStatus.UNPROCESSABLE_ENTITY,
                    "This Idempotency-Key was already used for a different cart");
        }
        if (record.order() != null) {
            return new Result(record.order(), true);
        }
        return place(cmd, record.request());      // an earlier attempt priced the cart but did not finish
    }

    private PricingClient.Quote quote(Command cmd, Cart cart) {
        return call(() -> pricing.quote(new PricingClient.QuoteRequest(cart.currency(), cmd.shippingAddress().zip(),
                cmd.couponCode(), cart.items().stream()
                .map(i -> new PricingClient.QuoteRequest.Line(i.sku(), i.quantity())).toList())));
    }

    private OrderRequest priced(Command cmd, Cart cart) {
        PricingClient.Quote quote = quote(cmd, cart);
        List<OrderRequest.Line> lines = quote.lines().stream().map(l -> new OrderRequest.Line(l.sku(), l.quantity(),
                l.unitPrice(), l.discount(), l.tax(), "SHIP")).toList();
        return new OrderRequest(cmd.customerId(), cmd.channel(), quote.currency(), lines, cmd.shippingAddress(),
                quote.shippingTotal(), quote.grandTotal());
    }

    private Result place(Command cmd, OrderRequest request) {
        ResponseEntity<OrderSummary> response = call(() -> orders.create(cmd.idempotencyKey(), request));
        OrderSummary order = response.getBody();
        boolean replayed = "true".equals(response.getHeaders().getFirst(OrderClient.REPLAYED));
        records.saveOrder(cmd.customerId(), cmd.idempotencyKey(), new CheckoutRecord(cmd.cartId(), request, order));
        carts.delete(cmd.cartId());      // architecture 8: "Deleted at checkout"
        log.info("Checkout placed order {} for cart {}", order.orderNumber(), cmd.cartId());
        return new Result(order, replayed);
    }

    /**
     * The Feign circuit breaker wraps whatever a call throws in NoFallbackAvailableException. A rejection by the other
     * service (an OmsException made by our error decoder) is handed on unchanged; anything else (connection refused,
     * timeout, open breaker) is "not available" for the customer.
     */
    private static <T> T call(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (NoFallbackAvailableException e) {
            if (e.getCause() instanceof OmsException rejection) {
                throw rejection;
            }
            throw unavailable(e.getCause());
        } catch (FeignException e) {
            throw unavailable(e);
        }
    }

    private static OmsException unavailable(Throwable cause) {
        log.error("Downstream call failed: {}", String.valueOf(cause));
        return new OmsException("CRT-503", HttpStatus.SERVICE_UNAVAILABLE,
                "A service needed for this request is not available, please try again shortly");
    }
}
