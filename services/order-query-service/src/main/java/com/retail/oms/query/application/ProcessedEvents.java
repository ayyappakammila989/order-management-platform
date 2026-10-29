package com.retail.oms.query.application;

/** Remembers which event ids this consumer has fully handled (architecture 8: evt:{consumer}:{eventId}). */
public interface ProcessedEvents {

    boolean contains(String eventId);

    void markProcessed(String eventId);
}
