package com.retail.oms.order.domain;

/**
 * How the order is paid: a gateway token from the client (Stripe PaymentMethod id or approved PayPal order id),
 * never card data (architecture 7.5). order-service only passes it on; payment-service authorizes with it.
 */
public record PaymentMethod(PaymentGateway gateway, String methodToken) {
}
