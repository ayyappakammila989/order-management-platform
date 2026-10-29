package com.retail.oms.inventory.application;

/** A command or event that can never be applied (unknown location, bad id): straight to the dead-letter topic. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }
}
