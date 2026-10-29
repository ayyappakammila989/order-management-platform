package com.retail.oms.orchestrator.application.engine;

import com.retail.oms.orchestrator.domain.SagaData;
import java.util.function.UnaryOperator;

public sealed interface StepResult {

    /** A command was sent; the saga waits for the reply to {@code commandId}. */
    record AwaitReply(String commandId) implements StepResult {
    }

    /** Finished now; {@code update} may store results in the saga data. */
    record Done(UnaryOperator<SagaData> update) implements StepResult {
    }

    /** A business "no" (out of stock, order cannot be confirmed): the saga compensates. */
    record Failed(String reason) implements StepResult {
    }

    static StepResult done() {
        return new Done(UnaryOperator.identity());
    }
}
