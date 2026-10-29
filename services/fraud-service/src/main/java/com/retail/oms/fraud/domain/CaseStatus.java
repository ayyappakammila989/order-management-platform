package com.retail.oms.fraud.domain;

/** OPEN waits for a supervisor; CLOSED = the order went away (saga compensated) before anyone decided. */
public enum CaseStatus {
    OPEN, APPROVED, REJECTED, CLOSED
}
