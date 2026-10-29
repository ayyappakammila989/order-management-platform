package com.retail.oms.orchestrator.application.steps;

import com.retail.oms.orchestrator.application.OrderRejectedException;
import com.retail.oms.orchestrator.application.OrderServicePort;
import com.retail.oms.orchestrator.application.engine.SagaStep;
import com.retail.oms.orchestrator.application.engine.StepContext;
import com.retail.oms.orchestrator.application.engine.StepResult;
import org.springframework.stereotype.Component;

/**
 * Last step (architecture 3.2 step 4): order-service sets CONFIRMED. Synchronous REST, as in the design's API table.
 * If order-service refuses (the customer cancelled meanwhile: ORD-409), the saga compensates. (Sending
 * oms.fulfillment.create-cmd comes with fulfillment-service in step 4.)
 */
@Component
public class ConfirmOrderStep implements SagaStep {

    public static final String NAME = "CONFIRM_ORDER";

    private final OrderServicePort orders;

    public ConfirmOrderStep(OrderServicePort orders) {
        this.orders = orders;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean compensable() {
        return false;      // the last step: nothing after it can fail
    }

    @Override
    public StepResult start(StepContext ctx) {
        try {
            orders.changeStatus(ctx.orderId(), "CONFIRMED", "Stock reserved (saga " + ctx.sagaId() + ")");
            return StepResult.done();
        } catch (OrderRejectedException e) {
            return new StepResult.Failed("order-service refused CONFIRMED: " + e.getMessage());
        }
    }

    @Override
    public StepResult compensate(StepContext ctx) {
        throw new UnsupportedOperationException(NAME + " is not compensable");
    }
}
