package com.retail.oms.orchestrator.application.engine;

import com.retail.oms.kafka.outbox.ProcessedMessages;
import com.retail.oms.orchestrator.config.SagaProperties;
import com.retail.oms.orchestrator.domain.OrderInfo;
import com.retail.oms.orchestrator.domain.SagaData;
import com.retail.oms.orchestrator.domain.SagaInstance;
import com.retail.oms.orchestrator.domain.SagaStatus;
import com.retail.oms.orchestrator.domain.SagaStepLog;
import com.retail.oms.orchestrator.domain.StepOutcome;
import com.retail.oms.orchestrator.infrastructure.persistence.SagaInstanceRepository;
import com.retail.oms.orchestrator.infrastructure.persistence.SagaStepLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration-based saga (architecture 3.2). Each public method handles one message (or one timeout) in ONE
 * transaction: the message is marked processed, the saga state changes, the step log grows and the next command goes
 * to the outbox - all or nothing. So a crash or a retry can never send a command twice for one state change.
 * <p>
 * Forward: steps run in order; a step either finishes at once or sends a command and waits for the reply.
 * Backward: when a step fails, the steps that completed are compensated in REVERSE order, one at a time, each
 * waiting for its own reply; then {@link SagaDefinition#onAborted} runs (cancel the order).
 * Replies are matched by command id: a duplicate, stale or late reply cannot move the saga by mistake.
 */
@Service
@Slf4j
public class SagaEngine {

    public static final String CONSUMER = "order-orchestrator";
    public static final String SAGA = "SAGA";

    private final SagaInstanceRepository sagas;
    private final SagaStepLogRepository stepLog;
    private final ProcessedMessages processed;
    private final Map<String, SagaDefinition> definitions;
    private final SagaProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public SagaEngine(SagaInstanceRepository sagas, SagaStepLogRepository stepLog, ProcessedMessages processed,
            List<SagaDefinition> definitions, SagaProperties props, Clock clock, MeterRegistry meters) {
        this.sagas = sagas;
        this.stepLog = stepLog;
        this.processed = processed;
        this.definitions = definitions.stream().collect(Collectors.toMap(SagaDefinition::type, Function.identity()));
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    /** oms.order.created: one saga per order; a second event for the same order (other event id) starts nothing. */
    @Transactional
    public Optional<SagaInstance> start(String type, OrderInfo order, String messageId, String correlationId) {
        if (!processed.markProcessed(CONSUMER, messageId)) {
            log.info("Event {} for order {} was handled before, skipping", messageId, order.orderNumber());
            return Optional.empty();
        }
        UUID orderId = UUID.fromString(order.orderId());
        if (sagas.findByOrderId(orderId).isPresent()) {
            log.info("Order {} already has a saga, not starting another", order.orderNumber());
            return Optional.empty();
        }
        SagaDefinition def = definition(type);
        SagaInstance saga = sagas.saveAndFlush(new SagaInstance(orderId, type, SagaData.start(order), now()));
        record(saga, SAGA, StepOutcome.STARTED, type + " for order " + order.orderNumber());
        meters.counter("oms.saga.started", "type", type).increment();
        runFrom(saga, def, 0, correlationId);
        return Optional.of(saga);
    }

    /** A participant says the step worked (e.g. oms.inventory.reserved). */
    @Transactional
    public void stepSucceeded(UUID sagaId, String step, String commandId, UnaryOperator<SagaData> update,
            String messageId, String correlationId) {
        if (!processed.markProcessed(CONSUMER, messageId)) {
            return;
        }
        Optional<SagaInstance> found = sagas.findById(sagaId);
        if (found.isEmpty()) {
            log.warn("Reply {} for unknown saga {} ignored", messageId, sagaId);
            return;
        }
        SagaInstance saga = found.get();
        SagaDefinition def = definition(saga.getSagaType());
        if (saga.getStatus() == SagaStatus.RUNNING && saga.isWaitingFor(step, commandId)) {
            saga.setPayload(update.apply(saga.getPayload()).withCompleted(step));
            saga.stopWaiting();
            record(saga, step, StepOutcome.SUCCEEDED, null);
            runFrom(saga, def, def.indexOf(step) + 1, correlationId);
        } else if (saga.getStatus() != SagaStatus.RUNNING && def.step(step).compensable()) {
            // e.g. stock reserved after the saga timed out or the customer cancelled: undo it right away
            saga.setPayload(update.apply(saga.getPayload()));
            record(saga, step, StepOutcome.LATE_REPLY, "arrived while " + saga.getStatus() + "; compensating it now");
            def.step(step).compensate(context(saga, correlationId));
            touch(saga);
        } else {
            log.info("Saga {}: reply for {} (command {}) is not awaited (status {}, waiting for {} {}), ignored",
                    sagaId, step, commandId, saga.getStatus(), saga.getCurrentStep(), saga.getPendingCommandId());
        }
    }

    /** A participant says no (e.g. oms.inventory.reserve-failed). */
    @Transactional
    public void stepFailed(UUID sagaId, String step, String commandId, String reason, String messageId,
            String correlationId) {
        if (!processed.markProcessed(CONSUMER, messageId)) {
            return;
        }
        sagas.findById(sagaId).ifPresentOrElse(saga -> {
            if (saga.getStatus() == SagaStatus.RUNNING && saga.isWaitingFor(step, commandId)) {
                saga.stopWaiting();
                record(saga, step, StepOutcome.FAILED, reason);
                beginCompensation(saga, definition(saga.getSagaType()), step + " failed: " + reason, false, correlationId);
            } else {
                log.info("Saga {}: failure of {} (command {}) is not awaited, ignored", sagaId, step, commandId);
            }
        }, () -> log.warn("Failure reply {} for unknown saga {} ignored", messageId, sagaId));
    }

    /** A compensation was done (e.g. oms.inventory.released answering a release command). */
    @Transactional
    public void compensated(UUID sagaId, String step, String commandId, String note, String messageId,
            String correlationId) {
        if (!processed.markProcessed(CONSUMER, messageId)) {
            return;
        }
        sagas.findById(sagaId).ifPresentOrElse(saga -> {
            if (saga.getStatus() == SagaStatus.COMPENSATING && saga.isWaitingFor(step, commandId)) {
                saga.stopWaiting();
                record(saga, step, StepOutcome.COMPENSATED, note);
                List<String> queue = saga.getPayload().compensationQueue();
                saga.setPayload(saga.getPayload().withCompensationQueue(queue.subList(1, queue.size())));
                saga.setAttempts(0);
                compensateNext(saga, definition(saga.getSagaType()), correlationId);
            } else {
                log.info("Saga {}: compensation reply for {} (command {}) is not awaited, ignored", sagaId, step, commandId);
            }
        }, () -> log.warn("Compensation reply {} for unknown saga {} ignored", messageId, sagaId));
    }

    /**
     * Something outside the saga ends it, e.g. the customer cancelled the order while the saga was running.
     * The step being waited for may or may not have happened, so it is compensated too (compensations are idempotent).
     */
    @Transactional
    public void abort(UUID orderId, String reason, boolean orderAlreadyCancelled, String messageId, String correlationId) {
        if (!processed.markProcessed(CONSUMER, messageId)) {
            return;
        }
        Optional<SagaInstance> found = sagas.findByOrderId(orderId);
        if (found.isEmpty()) {
            log.info("Order {}: no saga to abort ({})", orderId, reason);
            return;
        }
        SagaInstance saga = found.get();
        if (orderAlreadyCancelled) {
            saga.setPayload(saga.getPayload().withOrderCancelled());
        }
        switch (saga.getStatus()) {
            case RUNNING -> {
                saga.stopWaiting();
                record(saga, saga.getCurrentStep(), StepOutcome.FAILED, reason);
                beginCompensation(saga, definition(saga.getSagaType()), reason, true, correlationId);
            }
            case COMPLETED -> record(saga, SAGA, StepOutcome.ABORTED, reason + " after the saga completed; "
                    + "participants handle it themselves (inventory releases on oms.order.cancelled)");
            default -> log.info("Saga {} is {}, nothing to abort ({})", saga.getSagaId(), saga.getStatus(), reason);
        }
        touch(saga);
    }

    /**
     * Sagas whose reply did not come in time (architecture: saga_db survives restarts, so a lost reply is not lost
     * state). RUNNING: the step counts as failed (and is compensated, it may have happened). COMPENSATING: the
     * compensation command is sent again, up to {@code compensation-max-attempts}, then the saga is FAILED.
     *
     * @return number of sagas handled
     */
    @Transactional
    public int timeOutOverdue(int limit) {
        List<UUID> overdue = sagas.lockOverdue(now(), limit);
        for (UUID id : overdue) {
            SagaInstance saga = sagas.findById(id).orElseThrow();
            SagaDefinition def = definition(saga.getSagaType());
            String step = saga.getCurrentStep();
            if (saga.getStatus() == SagaStatus.RUNNING) {
                saga.stopWaiting();
                record(saga, step, StepOutcome.TIMED_OUT, "no reply within " + props.stepTimeout());
                beginCompensation(saga, def, step + " timed out", true, null);
            } else if (saga.getAttempts() + 1 >= props.compensationMaxAttempts()) {
                saga.stopWaiting();
                saga.setStatus(SagaStatus.FAILED);
                record(saga, step, StepOutcome.FAILED, "compensation got no reply after " + props.compensationMaxAttempts()
                        + " attempts: needs manual action");
                finished(saga);
            } else {
                saga.setAttempts(saga.getAttempts() + 1);
                record(saga, step, StepOutcome.TIMED_OUT, "compensation not confirmed, sending it again (attempt "
                        + (saga.getAttempts() + 1) + ")");
                compensateNext(saga, def, null);
            }
            touch(saga);
        }
        return overdue.size();
    }

    @Transactional(readOnly = true)
    public Optional<SagaInstance> find(UUID sagaId) {
        return sagas.findById(sagaId);
    }

    @Transactional(readOnly = true)
    public Optional<SagaInstance> findByOrder(UUID orderId) {
        return sagas.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public List<SagaStepLog> steps(UUID sagaId) {
        return stepLog.findBySagaIdOrderByIdAsc(sagaId);
    }

    private void runFrom(SagaInstance saga, SagaDefinition def, int index, String correlationId) {
        List<SagaStep> steps = def.steps();
        for (int i = index; i < steps.size(); i++) {
            SagaStep step = steps.get(i);
            saga.setCurrentStep(step.name());
            record(saga, step.name(), StepOutcome.STARTED, null);
            StepResult result = step.start(context(saga, correlationId));
            switch (result) {
                case StepResult.AwaitReply a -> {
                    saga.await(step.name(), a.commandId(), now().plus(props.stepTimeout()));
                    touch(saga);
                    return;
                }
                case StepResult.Done d -> {
                    saga.setPayload(d.update().apply(saga.getPayload()).withCompleted(step.name()));
                    record(saga, step.name(), StepOutcome.SUCCEEDED, null);
                }
                case StepResult.Failed f -> {
                    record(saga, step.name(), StepOutcome.FAILED, f.reason());
                    beginCompensation(saga, def, step.name() + " failed: " + f.reason(), false, correlationId);
                    return;
                }
            }
        }
        saga.setStatus(SagaStatus.COMPLETED);
        saga.setCurrentStep(null);
        saga.stopWaiting();
        record(saga, SAGA, StepOutcome.COMPLETED, null);
        finished(saga);
    }

    /** Compensations = completed compensable steps, newest first; plus the current one if its result is unknown. */
    private void beginCompensation(SagaInstance saga, SagaDefinition def, String reason, boolean includeCurrent,
            String correlationId) {
        saga.setStatus(SagaStatus.COMPENSATING);
        saga.setFailureReason(reason);
        saga.setAttempts(0);
        SagaData data = saga.getPayload();
        List<String> queue = new ArrayList<>(data.completedSteps().stream()
                .filter(name -> def.step(name).compensable()).toList());
        String current = saga.getCurrentStep();
        if (includeCurrent && current != null && !SAGA.equals(current) && def.step(current).compensable()
                && !queue.contains(current)) {
            queue.add(current);
        }
        Collections.reverse(queue);
        saga.setPayload(data.withCompensationQueue(queue));
        compensateNext(saga, def, correlationId);
    }

    private void compensateNext(SagaInstance saga, SagaDefinition def, String correlationId) {
        while (!saga.getPayload().compensationQueue().isEmpty()) {
            String name = saga.getPayload().compensationQueue().get(0);
            saga.setCurrentStep(name);
            record(saga, name, StepOutcome.COMPENSATING, saga.getFailureReason());
            StepResult result = def.step(name).compensate(context(saga, correlationId));
            switch (result) {
                case StepResult.AwaitReply a -> {
                    saga.await(name, a.commandId(), now().plus(props.stepTimeout()));
                    touch(saga);
                    return;
                }
                case StepResult.Done d -> {
                    record(saga, name, StepOutcome.COMPENSATED, null);
                    List<String> queue = saga.getPayload().compensationQueue();
                    saga.setPayload(d.update().apply(saga.getPayload()).withCompensationQueue(queue.subList(1, queue.size())));
                }
                case StepResult.Failed f -> {
                    saga.setStatus(SagaStatus.FAILED);
                    saga.stopWaiting();
                    record(saga, name, StepOutcome.FAILED, "compensation failed: " + f.reason() + "; needs manual action");
                    finished(saga);
                    return;
                }
            }
        }
        try {
            def.onAborted(context(saga, correlationId));
            saga.setStatus(SagaStatus.COMPENSATED);
            record(saga, SAGA, StepOutcome.ABORTED, saga.getFailureReason());
        } catch (StepRejectedException e) {
            saga.setStatus(SagaStatus.FAILED);
            record(saga, SAGA, StepOutcome.FAILED, "could not finish the compensation: " + e.getMessage());
        }
        saga.setCurrentStep(null);
        saga.stopWaiting();
        finished(saga);
    }

    private void finished(SagaInstance saga) {
        touch(saga);
        meters.timer("oms.saga.duration", "type", saga.getSagaType(), "outcome", saga.getStatus().name())
                .record(Duration.between(saga.getStartedAt(), now()));
    }

    private StepContext context(SagaInstance saga, String correlationId) {
        return new StepContext(saga.getSagaId(), saga.getOrderId(), saga.getPayload(), saga.getFailureReason(), correlationId);
    }

    private void record(SagaInstance saga, String step, StepOutcome outcome, String message) {
        stepLog.save(new SagaStepLog(saga.getSagaId(), step, outcome, message, now()));
        log.info("Saga {} order {}: {} {}{}", saga.getSagaId(), saga.getPayload().order().orderNumber(), step, outcome,
                message == null ? "" : " (" + message + ")");
    }

    private void touch(SagaInstance saga) {
        saga.setUpdatedAt(now());
    }

    private SagaDefinition definition(String type) {
        SagaDefinition def = definitions.get(type);
        if (def == null) {
            throw new IllegalStateException("No saga definition " + type);
        }
        return def;
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
