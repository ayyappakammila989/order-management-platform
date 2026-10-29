package com.retail.oms.order.domain;

/** Which payment gateway the customer chose at checkout (architecture 4.2: payment.gateway STRIPE/PAYPAL). */
public enum PaymentGateway {
    STRIPE, PAYPAL
}
