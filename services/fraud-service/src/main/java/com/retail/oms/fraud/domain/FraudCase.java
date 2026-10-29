package com.retail.oms.fraud.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A flagged check (architecture 2: "flagged cases for review"). Optimistic lock: two supervisors cannot both decide. */
@Entity
@Table(name = "fraud_case")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FraudCase {

    @Id
    private UUID caseId;
    private UUID checkId;
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    private CaseStatus status;
    private Instant openedAt;
    private String decidedBy;
    private Instant decidedAt;
    private String note;
    @Version
    private Long version;

    /** REVIEW opens a case; REJECTED is recorded as an already decided case (decided by the rules). */
    public static FraudCase forCheck(FraudCheck check, Instant now) {
        FraudCase c = new FraudCase();
        c.caseId = UUID.randomUUID();
        c.checkId = check.getCheckId();
        c.orderId = check.getOrderId();
        c.openedAt = now;
        if (check.getDecision() == Decision.REJECTED) {
            c.status = CaseStatus.REJECTED;
            c.decidedBy = "RULES";
            c.decidedAt = now;
            c.note = "Rejected by rules: " + check.getReasons();
        } else {
            c.status = CaseStatus.OPEN;
        }
        return c;
    }

    public boolean isOpen() {
        return status == CaseStatus.OPEN;
    }

    public void decide(boolean approve, String user, String note, Instant now) {
        if (!isOpen()) {
            throw new IllegalStateException("Case " + caseId + " is " + status);
        }
        status = approve ? CaseStatus.APPROVED : CaseStatus.REJECTED;
        decidedBy = user;
        decidedAt = now;
        this.note = note;
    }

    public boolean close(String reason, Instant now) {
        if (!isOpen()) {
            return false;
        }
        status = CaseStatus.CLOSED;
        decidedBy = "SYSTEM";
        decidedAt = now;
        note = reason.length() > 500 ? reason.substring(0, 500) : reason;
        return true;
    }
}
