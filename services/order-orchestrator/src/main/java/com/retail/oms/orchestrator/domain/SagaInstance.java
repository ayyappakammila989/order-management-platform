package com.retail.oms.orchestrator.domain;

import jakarta.persistence.Column;
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
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Saga state; survives restarts (architecture 4.1). {@code version}: two messages for the same saga handled at
 * once (e.g. a reply and the timeout job) cannot both win; the loser fails its UPDATE and is retried.
 */
@Entity
@Table(name = "saga_instance")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SagaInstance {

    @Id
    @Column(name = "saga_id")
    private UUID sagaId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "saga_type")
    private String sagaType;

    @Column(name = "current_step")
    private String currentStep;

    @Enumerated(EnumType.STRING)
    private SagaStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private SagaData payload;

    @Column(name = "pending_command_id")
    private String pendingCommandId;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    private int attempts;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    public SagaInstance(UUID orderId, String sagaType, SagaData payload, Instant now) {
        this.sagaId = UUID.randomUUID();
        this.orderId = orderId;
        this.sagaType = sagaType;
        this.status = SagaStatus.RUNNING;
        this.payload = payload;
        this.startedAt = now;
        this.updatedAt = now;
    }

    /** Waits for the reply to {@code commandId} until {@code deadline}. */
    public void await(String step, String commandId, Instant deadline) {
        this.currentStep = step;
        this.pendingCommandId = commandId;
        this.deadlineAt = deadline;
    }

    public void stopWaiting() {
        this.pendingCommandId = null;
        this.deadlineAt = null;
    }

    public boolean isWaitingFor(String step, String commandId) {
        return step.equals(currentStep) && commandId != null && commandId.equals(pendingCommandId);
    }

    public void setFailureReason(String reason) {
        this.failureReason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
    }
}
