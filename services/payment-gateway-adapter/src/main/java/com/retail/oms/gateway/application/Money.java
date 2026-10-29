package com.retail.oms.gateway.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Gateways count in minor units (cents). Only 2-decimal currencies are supported here (USD in this project). */
public final class Money {

    public static long toMinor(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    public static String plain(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private Money() {
    }
}
