package com.retail.oms.orchestrator.domain;

/** What happened to a step; one row in saga_step_log each time. */
public enum StepOutcome {
    STARTED,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    COMPENSATING,
    COMPENSATED,
    /** a reply that came after the saga had moved on (e.g. stock reserved after a timeout); undone right away */
    LATE_REPLY,
    /** for the saga itself (step "SAGA") */
    COMPLETED,
    ABORTED
}
