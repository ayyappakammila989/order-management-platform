package com.retail.oms.fraud.domain;

public enum RuleScope {
    ORDER, REFUND, ANY;

    public boolean covers(CheckKind kind) {
        return this == ANY || name().equals(kind.name());
    }
}
