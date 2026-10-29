package com.retail.oms.fraud.application;

/** A message that can never be processed (bad id, missing field): dead-lettered without retries. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }
}
