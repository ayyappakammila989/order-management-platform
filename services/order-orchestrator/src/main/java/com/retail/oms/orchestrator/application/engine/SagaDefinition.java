package com.retail.oms.orchestrator.application.engine;

import java.util.List;

/** A kind of saga: its steps in order and what to do at the end of a compensation. */
public interface SagaDefinition {

    String type();

    List<SagaStep> steps();

    /**
     * Called once all compensations are done (e.g. cancel the order). Throw {@link StepRejectedException} when the
     * other side refuses; the saga then ends FAILED for a person to look at. Technical errors are thrown as they are.
     */
    void onAborted(StepContext ctx);

    default SagaStep step(String name) {
        return steps().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(type() + " has no step " + name));
    }

    default int indexOf(String name) {
        return steps().indexOf(step(name));
    }
}
