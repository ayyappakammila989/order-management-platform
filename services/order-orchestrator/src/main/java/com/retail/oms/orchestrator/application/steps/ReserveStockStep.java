package com.retail.oms.orchestrator.application.steps;

import com.retail.oms.events.Topics;
import com.retail.oms.events.inventory.ReleaseStockCommand;
import com.retail.oms.events.inventory.ReserveStockCommand;
import com.retail.oms.events.inventory.SkuQuantity;
import com.retail.oms.kafka.outbox.Outbox;
import com.retail.oms.orchestrator.application.engine.SagaStep;
import com.retail.oms.orchestrator.application.engine.StepContext;
import com.retail.oms.orchestrator.application.engine.StepResult;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Saga step 1 (architecture 3.2): reserve-cmd to inventory-service; compensation: release-cmd. */
@Component
public class ReserveStockStep implements SagaStep {

    public static final String NAME = "RESERVE_STOCK";
    public static final String RESERVATION_ID = "reservationId";

    private final Outbox outbox;
    private final Clock clock;

    public ReserveStockStep(Outbox outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean compensable() {
        return true;
    }

    @Override
    public StepResult start(StepContext ctx) {
        String commandId = UUID.randomUUID().toString();
        var order = ctx.data().order();
        var command = ReserveStockCommand.newBuilder().setCommandId(commandId).setOccurredAt(Instant.now(clock))
                .setSagaId(ctx.sagaId().toString()).setOrderId(order.orderId()).setShipToZip(order.shipToZip())
                .setLines(order.lines().stream().map(l -> new SkuQuantity(l.sku(), l.quantity())).toList())
                .build();
        outbox.add(Topics.INVENTORY_RESERVE_CMD, order.orderId(), commandId, command, ctx.correlationId());
        return new StepResult.AwaitReply(commandId);
    }

    @Override
    public StepResult compensate(StepContext ctx) {
        String commandId = UUID.randomUUID().toString();
        var command = ReleaseStockCommand.newBuilder().setCommandId(commandId).setOccurredAt(Instant.now(clock))
                .setSagaId(ctx.sagaId().toString()).setOrderId(ctx.orderId().toString())
                .setReason(ctx.failureReason() == null ? "saga compensation" : ctx.failureReason())
                .build();
        outbox.add(Topics.INVENTORY_RELEASE_CMD, ctx.orderId().toString(), commandId, command, ctx.correlationId());
        return new StepResult.AwaitReply(commandId);
    }
}
