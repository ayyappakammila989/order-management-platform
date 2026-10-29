package com.retail.oms.orchestrator.api;

import com.retail.oms.orchestrator.application.engine.SagaEngine;
import com.retail.oms.orchestrator.application.engine.SagaNotFoundException;
import com.retail.oms.orchestrator.domain.SagaInstance;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /internal/v1/sagas/{sagaId} (architecture 2: "saga state, for support"), and the same by order id. */
@RestController
@RequestMapping("/internal/v1/sagas")
@Tag(name = "Sagas (internal)")
public class SagaController {

    public record StepEntry(String step, String outcome, String message, Instant at) {
    }

    public record SagaResponse(UUID sagaId, UUID orderId, String orderNumber, String type, String status,
                               String currentStep, String failureReason, Map<String, String> results,
                               Instant startedAt, Instant updatedAt, Instant deadlineAt, List<StepEntry> steps) {
    }

    private final SagaEngine engine;

    public SagaController(SagaEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/{sagaId}")
    @Operation(summary = "Saga state and its step log")
    public SagaResponse get(@PathVariable UUID sagaId) {
        return engine.find(sagaId).map(this::toResponse)
                .orElseThrow(() -> new SagaNotFoundException(sagaId.toString()));
    }

    @GetMapping
    @Operation(summary = "The saga of an order")
    public SagaResponse byOrder(@RequestParam UUID orderId) {
        return engine.findByOrder(orderId).map(this::toResponse)
                .orElseThrow(() -> new SagaNotFoundException("for order " + orderId));
    }

    private SagaResponse toResponse(SagaInstance s) {
        List<StepEntry> steps = engine.steps(s.getSagaId()).stream()
                .map(l -> new StepEntry(l.getStep(), l.getOutcome().name(), l.getMessage(), l.getCreatedAt())).toList();
        return new SagaResponse(s.getSagaId(), s.getOrderId(), s.getPayload().order().orderNumber(), s.getSagaType(),
                s.getStatus().name(), s.getCurrentStep(), s.getFailureReason(), s.getPayload().results(),
                s.getStartedAt(), s.getUpdatedAt(), s.getDeadlineAt(), steps);
    }
}
