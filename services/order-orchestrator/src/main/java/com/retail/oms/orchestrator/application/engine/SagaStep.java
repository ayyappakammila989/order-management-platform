package com.retail.oms.orchestrator.application.engine;

/**
 * One step of a saga. {@link #start} either finishes right away (a synchronous call), or sends a command and waits
 * for the reply, or reports a business failure. Technical failures are thrown: the transaction rolls back and the
 * message that triggered the step is retried.
 */
public interface SagaStep {

    String name();

    /** Does this step leave something behind that must be undone when a later step fails? */
    boolean compensable();

    StepResult start(StepContext ctx);

    /** Undo; only called when {@link #compensable()}. Must be safe to repeat (it is, after timeouts and late replies). */
    StepResult compensate(StepContext ctx);
}
