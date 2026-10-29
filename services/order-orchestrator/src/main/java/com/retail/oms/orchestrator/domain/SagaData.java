package com.retail.oms.orchestrator.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * saga_instance.payload (JSONB). Immutable: every change makes a new value, so Hibernate sees the change.
 *
 * @param results           what steps produced, e.g. reservationId
 * @param completedSteps    steps that succeeded, in order (the ones that may need compensation)
 * @param compensationQueue compensations still to run, next first (= completed steps in reverse order)
 * @param orderCancelled    the order is already cancelled (by the customer), so the saga must not cancel it again
 */
public record SagaData(OrderInfo order, Map<String, String> results, List<String> completedSteps,
                       List<String> compensationQueue, boolean orderCancelled) {

    public static SagaData start(OrderInfo order) {
        return new SagaData(order, Map.of(), List.of(), List.of(), false);
    }

    public SagaData withResult(String key, String value) {
        var copy = new HashMap<>(results);
        copy.put(key, value);
        return new SagaData(order, Map.copyOf(copy), completedSteps, compensationQueue, orderCancelled);
    }

    public SagaData withCompleted(String step) {
        var copy = new ArrayList<>(completedSteps);
        copy.add(step);
        return new SagaData(order, results, List.copyOf(copy), compensationQueue, orderCancelled);
    }

    public SagaData withCompensationQueue(List<String> queue) {
        return new SagaData(order, results, completedSteps, List.copyOf(queue), orderCancelled);
    }

    public SagaData withOrderCancelled() {
        return new SagaData(order, results, completedSteps, compensationQueue, true);
    }

    public String result(String key) {
        return results.get(key);
    }
}
