package com.retail.oms.fraud.application;

import com.retail.oms.fraud.domain.CaseStatus;
import com.retail.oms.fraud.domain.CheckKind;
import com.retail.oms.fraud.domain.Decision;
import com.retail.oms.fraud.domain.Evaluation;
import com.retail.oms.fraud.domain.FraudCase;
import com.retail.oms.fraud.domain.FraudCheck;
import com.retail.oms.fraud.domain.RuleEngine;
import com.retail.oms.fraud.domain.Subject;
import com.retail.oms.fraud.infrastructure.persistence.FraudCaseRepository;
import com.retail.oms.fraud.infrastructure.persistence.FraudCheckRepository;
import com.retail.oms.fraud.infrastructure.persistence.FraudRuleRepository;
import com.retail.oms.kafka.outbox.ProcessedMessages;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Screens orders (saga step FRAUD_CHECK, architecture 3.2 step 2) and refunds (REST, architecture 3.5 step 2).
 * APPROVED and REJECTED answer the saga at once; REVIEW opens a case and the saga waits until a supervisor
 * decides it, then gets APPROVED or REJECTED with the same command id.
 */
@Service
@Slf4j
public class FraudService {

    public static final String CONSUMER = "fraud-service";

    /** Result of a screening. {@code caseId} is set for REVIEW and REJECTED. */
    public record Screening(FraudCheck check, Optional<FraudCase> fraudCase) {
    }

    private final FraudRuleRepository rules;
    private final FraudCheckRepository checks;
    private final FraudCaseRepository cases;
    private final RuleEngine.History history;
    private final RuleEngine engine;
    private final ProcessedMessages processed;
    private final FraudEvents events;
    private final Clock clock;
    private final MeterRegistry meters;

    public FraudService(FraudRuleRepository rules, FraudCheckRepository checks, FraudCaseRepository cases,
            RuleEngine.History history, FraudProperties props, ProcessedMessages processed, FraudEvents events,
            Clock clock, MeterRegistry meters) {
        this.rules = rules;
        this.checks = checks;
        this.cases = cases;
        this.history = history;
        this.engine = new RuleEngine(props.reviewScore(), props.rejectScore());
        this.processed = processed;
        this.events = events;
        this.clock = clock;
        this.meters = meters;
    }

    /** oms.fraud.check-cmd. The same command twice (re-delivery) is answered once. */
    @Transactional
    public Optional<Screening> screenForSaga(Subject subject, UUID orderId, SagaRef saga) {
        if (!processed.markProcessed(CONSUMER, saga.commandId()) || checks.findByCommandId(saga.commandId()).isPresent()) {
            log.info("Fraud check command {} for order {} was handled before, skipping", saga.commandId(), orderId);
            return Optional.empty();
        }
        Screening s = screen(subject, orderId, null, saga);
        FraudCheck check = s.check();
        String caseId = s.fraudCase().map(c -> c.getCaseId().toString()).orElse(null);
        events.decision(check, check.getDecision(), caseId, "RULES", saga.correlationId());
        return Optional.of(s);
    }

    /** POST /internal/v1/fraud/check: synchronous, no saga reply (e.g. returns-service before a refund). */
    @Transactional
    public Screening screenNow(Subject subject, UUID orderId, String referenceId) {
        return screen(subject, orderId, referenceId, null);
    }

    /**
     * oms.fraud.cancel-cmd (saga compensation): the order is not going ahead. The check is kept (it still counts
     * for the velocity rules) but marked CANCELLED, and an open review case is closed so nobody reviews a dead order.
     * Always answered, also when there was nothing to cancel, so the saga can go on.
     */
    @Transactional
    public void cancelForSaga(UUID orderId, String reason, SagaRef saga) {
        if (!processed.markProcessed(CONSUMER, saga.commandId())) {
            return;
        }
        Instant now = Instant.now(clock);
        List<FraudCheck> found = checks.findByOrderIdAndKindOrderByCreatedAtDesc(orderId, CheckKind.ORDER);
        String note = "NOTHING_TO_CANCEL";
        for (FraudCheck check : found) {
            if (check.cancel(now)) {
                note = "CHECK_CANCELLED";
            }
            Optional<FraudCase> open = cases.findByCheckId(check.getCheckId()).filter(FraudCase::isOpen);
            if (open.isPresent()) {
                open.get().close("Order will not go ahead: " + reason, now);
                note = "CASE_CLOSED";
            }
        }
        log.info("Fraud check of order {} cancelled by saga {}: {}", orderId, saga.sagaId(), note);
        events.cancelled(saga, orderId, note);
    }

    /** A supervisor decides an open case; the waiting saga gets the final decision. */
    @Transactional
    public FraudCase decide(UUID caseId, boolean approve, String user, String note, String correlationId) {
        FraudCase fraudCase = cases.findById(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        if (!fraudCase.isOpen()) {
            throw new CaseNotOpenException("Case " + caseId + " is already " + fraudCase.getStatus()
                    + (fraudCase.getStatus() == CaseStatus.CLOSED ? " (the order was cancelled)" : ""));
        }
        Instant now = Instant.now(clock);
        FraudCheck check = checks.findById(fraudCase.getCheckId()).orElseThrow();
        Decision decided = approve ? Decision.APPROVED : Decision.REJECTED;
        fraudCase.decide(approve, user, note, now);
        check.decideAfterReview(decided, now);
        if (check.getCommandId() != null && check.getSagaId() != null) {
            events.decision(check, decided, caseId.toString(), user, correlationId);
        }
        meters.counter("oms.fraud.cases.decided", "decision", decided.name()).increment();
        log.info("Case {} for order {} {} by {}", caseId, check.getOrderId(), decided, user);
        return fraudCase;
    }

    @Transactional(readOnly = true)
    public List<FraudCase> cases(CaseStatus status, int limit) {
        return cases.findByStatusOrderByOpenedAtAsc(status, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public FraudCase fraudCase(UUID caseId) {
        return cases.findById(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
    }

    @Transactional(readOnly = true)
    public FraudCheck check(UUID checkId) {
        return checks.findById(checkId).orElseThrow();
    }

    private Screening screen(Subject subject, UUID orderId, String referenceId, SagaRef saga) {
        Instant now = Instant.now(clock);
        Evaluation result = engine.evaluate(rules.findAll(), subject, history);
        FraudCheck check = checks.save(FraudCheck.record(subject, orderId, referenceId,
                saga == null ? null : saga.sagaId(), saga == null ? null : saga.commandId(), result, now));
        Optional<FraudCase> fraudCase = Optional.empty();
        if (result.decision() != Decision.APPROVED) {
            fraudCase = Optional.of(cases.save(FraudCase.forCheck(check, now)));
        }
        meters.counter("oms.fraud.checks", "kind", subject.kind().name(), "decision", result.decision().name()).increment();
        log.info("Fraud {} check for order {}: {} (score {}{})", subject.kind(), orderId, result.decision(), result.score(),
                result.reasons().isEmpty() ? "" : ", " + String.join(", ", result.reasons()));
        return new Screening(check, fraudCase);
    }
}
