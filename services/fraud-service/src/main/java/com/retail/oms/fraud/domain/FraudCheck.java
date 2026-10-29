package com.retail.oms.fraud.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One screening and its outcome. */
@Entity
@Table(name = "fraud_check")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FraudCheck {

    public enum Status { ACTIVE, CANCELLED }

    @Id
    private UUID checkId;
    @Enumerated(EnumType.STRING)
    private CheckKind kind;
    private UUID orderId;
    private String referenceId;
    private String customerId;
    private UUID sagaId;
    private String commandId;
    private BigDecimal amount;
    private String currency;
    private String shipToZip;
    private String paymentToken;
    private int score;
    @Enumerated(EnumType.STRING)
    private Decision decision;
    @Enumerated(EnumType.STRING)
    private Decision finalDecision;
    private String reasons;
    @Enumerated(EnumType.STRING)
    private Status status;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;

    public static FraudCheck record(Subject s, UUID orderId, String referenceId, UUID sagaId, String commandId,
            Evaluation result, Instant now) {
        FraudCheck c = new FraudCheck();
        c.checkId = UUID.randomUUID();
        c.kind = s.kind();
        c.orderId = orderId;
        c.referenceId = referenceId;
        c.customerId = s.customerId();
        c.sagaId = sagaId;
        c.commandId = commandId;
        c.amount = s.amount();
        c.currency = s.currency();
        c.shipToZip = s.shipToZip();
        c.paymentToken = s.paymentToken();
        c.score = result.score();
        c.decision = result.decision();
        c.reasons = String.join(",", result.reasons());
        c.status = Status.ACTIVE;
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    public List<String> reasonList() {
        return reasons.isEmpty() ? List.of() : Arrays.asList(reasons.split(","));
    }

    /** The decision that counts now: the rules', or the supervisor's after a review. */
    public Decision effectiveDecision() {
        return finalDecision != null ? finalDecision : decision;
    }

    public void decideAfterReview(Decision decided, Instant now) {
        if (decided == Decision.REVIEW) {
            throw new IllegalArgumentException("A review ends in APPROVED or REJECTED");
        }
        this.finalDecision = decided;
        this.updatedAt = now;
    }

    public boolean cancel(Instant now) {
        if (status == Status.CANCELLED) {
            return false;
        }
        status = Status.CANCELLED;
        updatedAt = now;
        return true;
    }
}
