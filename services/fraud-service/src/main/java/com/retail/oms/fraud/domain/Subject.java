package com.retail.oms.fraud.domain;

import java.math.BigDecimal;
import java.util.List;

/** What is screened. paymentToken / shipToZip may be null (refunds have neither). */
public record Subject(CheckKind kind, String customerId, BigDecimal amount, String currency, String shipToZip,
                      String paymentToken, List<Line> lines) {

    public record Line(String sku, int quantity) {
    }
}
