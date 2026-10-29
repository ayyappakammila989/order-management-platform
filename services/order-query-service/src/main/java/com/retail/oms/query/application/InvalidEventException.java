package com.retail.oms.query.application;

/** The event itself is wrong, so trying again cannot help: it goes straight to the dead-letter topic. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
