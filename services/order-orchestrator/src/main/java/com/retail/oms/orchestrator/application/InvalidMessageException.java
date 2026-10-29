package com.retail.oms.orchestrator.application;

/** A message that can never be handled (bad ids, unexpected type): straight to the dead-letter topic. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }
}
