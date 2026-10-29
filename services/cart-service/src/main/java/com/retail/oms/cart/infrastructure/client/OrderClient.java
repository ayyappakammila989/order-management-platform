package com.retail.oms.cart.infrastructure.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** order-service internal API. The contract lives in order-service/src/test/resources/contracts/cart. */
@FeignClient(name = "order-service", url = "${oms.order-service.url:}")
public interface OrderClient {

    String IDEMPOTENCY_KEY = "Idempotency-Key";
    String REPLAYED = "Idempotent-Replayed";

    @PostMapping("/internal/v1/orders")
    ResponseEntity<OrderSummary> create(@RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody OrderRequest request);

    /** Same shape as order-service's CreateOrderRequest. Discount and tax are line totals. */
    record OrderRequest(String customerId, String channel, String currency, List<Line> lines, Address shippingAddress,
                        BigDecimal shippingTotal, BigDecimal expectedGrandTotal, PaymentMethod paymentMethod) {

        /** Gateway token only (Stripe pm_..., PayPal order id); card data never reaches the platform. */
        public record PaymentMethod(String gateway, String methodToken) {
        }

        public record Line(String sku, int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal tax,
                           String fulfillmentType) {
        }

        public record Address(String name, String line1, String city, String state, String zip) {

            @Override
            public String toString() {
                return "Address[zip=" + zip + "]";
            }
        }
    }

    record OrderSummary(UUID orderId, String orderNumber, String customerId, String status, String currency,
                        BigDecimal subtotal, BigDecimal grandTotal) {
    }
}
