package com.retail.oms.fraud.domain;

/** What is screened: an order (saga step 2) or a refund (architecture 3.5, returns-service / payment-service). */
public enum CheckKind {
    ORDER, REFUND
}
