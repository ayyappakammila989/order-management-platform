package com.retail.oms.orchestrator.application.engine;

/** A participant gave a business "no" that the saga cannot work around. */
public class StepRejectedException extends RuntimeException {

    public StepRejectedException(String message) {
        super(message);
    }
}
