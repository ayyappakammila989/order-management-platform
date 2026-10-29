package com.retail.oms.orchestrator.application.steps;

import com.retail.oms.orchestrator.application.OrderRejectedException;
import com.retail.oms.orchestrator.application.OrderServicePort;
import com.retail.oms.orchestrator.application.engine.SagaDefinition;
import com.retail.oms.orchestrator.application.engine.SagaStep;
import com.retail.oms.orchestrator.application.engine.StepContext;
import com.retail.oms.orchestrator.application.engine.StepRejectedException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The order placement saga (architecture 3.2). Part A: reserve stock, then confirm the order. Part B puts the fraud
 * check and the payment authorization between the two; compensation order then becomes void, release, cancel.
 */
@Component
public class OrderPlacementSaga implements SagaDefinition {

    public static final String TYPE = "ORDER_PLACEMENT";

    private final List<SagaStep> steps;
    private final OrderServicePort orders;

    public OrderPlacementSaga(ReserveStockStep reserveStock, ConfirmOrderStep confirmOrder, OrderServicePort orders) {
        this.steps = List.of(reserveStock, confirmOrder);
        this.orders = orders;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<SagaStep> steps() {
        return steps;
    }

    /** All compensations done: cancel the order (unless the customer already did). */
    @Override
    public void onAborted(StepContext ctx) {
        if (ctx.data().orderCancelled()) {
            return;
        }
        String reason = ctx.failureReason() == null ? "Order could not be completed" : ctx.failureReason();
        try {
            orders.changeStatus(ctx.orderId(), "CANCELLED", reason.length() > 200 ? reason.substring(0, 200) : reason);
        } catch (OrderRejectedException e) {
            throw new StepRejectedException("order-service refused CANCELLED: " + e.getMessage());
        }
    }
}
