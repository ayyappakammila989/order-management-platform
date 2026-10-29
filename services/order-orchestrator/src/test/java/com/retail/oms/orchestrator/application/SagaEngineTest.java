package com.retail.oms.orchestrator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retail.oms.kafka.outbox.ProcessedMessages;
import com.retail.oms.orchestrator.application.engine.SagaDefinition;
import com.retail.oms.orchestrator.application.engine.SagaEngine;
import com.retail.oms.orchestrator.application.engine.SagaStep;
import com.retail.oms.orchestrator.application.engine.StepContext;
import com.retail.oms.orchestrator.application.engine.StepResult;
import com.retail.oms.orchestrator.config.SagaProperties;
import com.retail.oms.orchestrator.domain.OrderInfo;
import com.retail.oms.orchestrator.domain.SagaInstance;
import com.retail.oms.orchestrator.domain.SagaStatus;
import com.retail.oms.orchestrator.domain.SagaStepLog;
import com.retail.oms.orchestrator.infrastructure.persistence.SagaInstanceRepository;
import com.retail.oms.orchestrator.infrastructure.persistence.SagaStepLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The engine with three fake steps A, B, C (architecture 12.1 "saga logic"): it picks the right next step, and a
 * failure compensates the completed steps in REVERSE order, each waiting for its own reply. No Spring, no database:
 * repositories are in-memory maps.
 */
class SagaEngineTest {

    /** A step that sends a "command" (records it) or finishes/fails at once, as configured. */
    static final class FakeStep implements SagaStep {
        final String name;
        final boolean compensable;
        StepResult onStart;
        final List<String> calls;
        int commands;

        FakeStep(String name, boolean compensable, List<String> calls) {
            this.name = name;
            this.compensable = compensable;
            this.calls = calls;
        }

        String nextCommand(String kind) {
            String id = name + "-" + kind + "-" + (++commands);
            calls.add(id);
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean compensable() {
            return compensable;
        }

        @Override
        public StepResult start(StepContext ctx) {
            return onStart != null ? onStart : new StepResult.AwaitReply(nextCommand("do"));
        }

        @Override
        public StepResult compensate(StepContext ctx) {
            return new StepResult.AwaitReply(nextCommand("undo"));
        }
    }

    final List<String> calls = new ArrayList<>();
    final FakeStep a = new FakeStep("A", true, calls);
    final FakeStep b = new FakeStep("B", true, calls);
    final FakeStep c = new FakeStep("C", false, calls);
    final List<StepContext> aborted = new ArrayList<>();
    final Map<UUID, SagaInstance> store = new HashMap<>();
    final List<SagaStepLog> log = new ArrayList<>();
    final Set<String> processedIds = new HashSet<>();
    Instant now = Instant.parse("2026-10-29T10:00:00Z");
    SagaEngine engine;

    @BeforeEach
    void setUp() {
        SagaDefinition def = new SagaDefinition() {
            public String type() {
                return "TEST";
            }

            public List<SagaStep> steps() {
                return List.of(a, b, c);
            }

            public void onAborted(StepContext ctx) {
                aborted.add(ctx);
            }
        };
        var sagas = mock(SagaInstanceRepository.class);
        when(sagas.saveAndFlush(any())).thenAnswer(inv -> {
            SagaInstance s = inv.getArgument(0);
            store.put(s.getSagaId(), s);
            return s;
        });
        when(sagas.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));
        when(sagas.findByOrderId(any())).thenAnswer(inv -> store.values().stream()
                .filter(s -> s.getOrderId().equals(inv.getArgument(0))).findFirst());
        when(sagas.lockOverdue(any(), anyInt())).thenAnswer(inv -> store.values().stream()
                .filter(s -> s.getDeadlineAt() != null && s.getDeadlineAt().isBefore(inv.getArgument(0)))
                .map(SagaInstance::getSagaId).toList());
        var steps = mock(SagaStepLogRepository.class);
        when(steps.save(any())).thenAnswer(inv -> {
            log.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        var processed = mock(ProcessedMessages.class);
        when(processed.markProcessed(anyString(), anyString())).thenAnswer(inv -> processedIds.add(inv.getArgument(1)));
        Clock clock = new Clock() {
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return now;
            }
        };
        engine = new SagaEngine(sagas, steps, processed, List.of(def),
                new SagaProperties(Duration.ofSeconds(30), 3, Duration.ofSeconds(5)), clock, new SimpleMeterRegistry());
    }

    SagaInstance start() {
        var order = new OrderInfo(UUID.randomUUID().toString(), "OMS-1", "cust-1", "10001", "USD", "10.00",
                List.of(new OrderInfo.Line("SKU-1", 1)));
        return engine.start("TEST", order, UUID.randomUUID().toString(), null).orElseThrow();
    }

    void succeeded(SagaInstance s, String step, String command) {
        engine.stepSucceeded(s.getSagaId(), step, command, UnaryOperator.identity(), UUID.randomUUID().toString(), null);
    }

    void compensated(SagaInstance s, String step, String command) {
        engine.compensated(s.getSagaId(), step, command, null, UUID.randomUUID().toString(), null);
    }

    List<String> trail() {
        return log.stream().map(l -> l.getStep() + " " + l.getOutcome()).toList();
    }

    @Test
    void stepsRunInOrderAndTheSagaCompletes() {
        c.onStart = StepResult.done();
        SagaInstance s = start();
        succeeded(s, "A", "A-do-1");
        succeeded(s, "B", "B-do-1");

        assertThat(s.getStatus()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(calls).containsExactly("A-do-1", "B-do-1");
        assertThat(trail()).containsExactly("SAGA STARTED", "A STARTED", "A SUCCEEDED", "B STARTED", "B SUCCEEDED",
                "C STARTED", "C SUCCEEDED", "SAGA COMPLETED");
        assertThat(aborted).isEmpty();
    }

    @Test
    void failureOfTheThirdStepCompensatesTheOthersInReverseOrderOneAtATime() {
        c.onStart = new StepResult.Failed("payment declined");
        SagaInstance s = start();
        succeeded(s, "A", "A-do-1");
        succeeded(s, "B", "B-do-1");

        assertThat(s.getStatus()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(calls).containsExactly("A-do-1", "B-do-1", "B-undo-2");      // B first, A not yet
        assertThat(s.getCurrentStep()).isEqualTo("B");

        compensated(s, "B", "B-undo-2");
        assertThat(calls).last().isEqualTo("A-undo-2");                          // only now A
        assertThat(aborted).isEmpty();

        compensated(s, "A", "A-undo-2");
        assertThat(s.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(aborted).singleElement().satisfies(ctx -> assertThat(ctx.failureReason()).isEqualTo("C failed: payment declined"));
        assertThat(trail()).containsSubsequence("C FAILED", "B COMPENSATING", "B COMPENSATED", "A COMPENSATING",
                "A COMPENSATED", "SAGA ABORTED");
    }

    @Test
    void firstStepFailingHasNothingToCompensate() {
        SagaInstance s = start();
        engine.stepFailed(s.getSagaId(), "A", "A-do-1", "out of stock", UUID.randomUUID().toString(), null);

        assertThat(s.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(calls).containsExactly("A-do-1");                             // no undo of A: it never happened
        assertThat(aborted).hasSize(1);
    }

    @Test
    void repliesThatAreNotAwaitedChangeNothing() {
        SagaInstance s = start();
        succeeded(s, "A", "A-do-999");                                            // wrong command id
        succeeded(s, "B", "B-do-1");                                              // a step that has not started
        assertThat(s.getCurrentStep()).isEqualTo("A");
        assertThat(s.getStatus()).isEqualTo(SagaStatus.RUNNING);

        String messageId = UUID.randomUUID().toString();
        engine.stepSucceeded(s.getSagaId(), "A", "A-do-1", UnaryOperator.identity(), messageId, null);
        engine.stepSucceeded(s.getSagaId(), "A", "A-do-1", UnaryOperator.identity(), messageId, null);   // re-delivered
        assertThat(calls).containsExactly("A-do-1", "B-do-1");                    // B started once
    }

    @Test
    void timeoutCompensatesTheUnansweredStepAndALateReplyIsUndoneAgain() {
        SagaInstance s = start();
        now = now.plusSeconds(31);

        assertThat(engine.timeOutOverdue(10)).isEqualTo(1);
        assertThat(s.getStatus()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(calls).containsExactly("A-do-1", "A-undo-2");                 // A may have happened: undo it

        compensated(s, "A", "A-undo-2");
        assertThat(s.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        succeeded(s, "A", "A-do-1");                                              // the reply finally arrives
        assertThat(calls).last().isEqualTo("A-undo-3");                          // undone again, right away
        assertThat(trail()).contains("A TIMED_OUT", "A LATE_REPLY");
        assertThat(s.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    void compensationWithoutReplyIsResentAndThenNeedsAPerson() {
        c.onStart = new StepResult.Failed("no");
        SagaInstance s = start();
        succeeded(s, "A", "A-do-1");
        succeeded(s, "B", "B-do-1");                                              // now waiting for B-undo-2

        now = now.plusSeconds(31);
        engine.timeOutOverdue(10);
        assertThat(calls).last().isEqualTo("B-undo-3");                          // sent again
        now = now.plusSeconds(31);
        engine.timeOutOverdue(10);
        assertThat(calls).last().isEqualTo("B-undo-4");
        now = now.plusSeconds(31);
        engine.timeOutOverdue(10);

        assertThat(s.getStatus()).isEqualTo(SagaStatus.FAILED);                  // 3 attempts, then manual
        assertThat(aborted).isEmpty();                                            // A was never undone: no cancel
    }

    @Test
    void customerCancellingWhileRunningCompensatesTheCurrentStepAndDoesNotCancelAgain() {
        SagaInstance s = start();
        engine.abort(s.getOrderId(), "Order cancelled by customer", true, UUID.randomUUID().toString(), null);

        assertThat(calls).containsExactly("A-do-1", "A-undo-2");
        compensated(s, "A", "A-undo-2");
        assertThat(s.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(aborted).singleElement().satisfies(ctx -> assertThat(ctx.data().orderCancelled()).isTrue());
    }

    @Test
    void secondOrderCreatedEventForTheSameOrderStartsNoSecondSaga() {
        SagaInstance s = start();
        var order = s.getPayload().order();

        assertThat(engine.start("TEST", order, UUID.randomUUID().toString(), null)).isEmpty();
        assertThat(store).hasSize(1);
        assertThat(calls).containsExactly("A-do-1");
    }
}
