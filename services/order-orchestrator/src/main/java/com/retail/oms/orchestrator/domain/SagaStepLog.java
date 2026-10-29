package com.retail.oms.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "saga_step_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SagaStepLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_id")
    private UUID sagaId;

    private String step;

    @Enumerated(EnumType.STRING)
    private StepOutcome outcome;

    private String message;

    @Column(name = "created_at")
    private Instant createdAt;

    public SagaStepLog(UUID sagaId, String step, StepOutcome outcome, String message, Instant at) {
        this.sagaId = sagaId;
        this.step = step;
        this.outcome = outcome;
        this.message = message == null || message.length() <= 500 ? message : message.substring(0, 500);
        this.createdAt = at;
    }
}
