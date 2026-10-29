package com.retail.oms.order.application;

import com.retail.oms.order.domain.Order;
import com.retail.oms.order.domain.OrderStatus;
import com.retail.oms.order.exception.InvalidOrderStateException;
import com.retail.oms.order.exception.OrderNotFoundException;
import com.retail.oms.order.exception.OrderTotalsMismatchException;
import com.retail.oms.order.infrastructure.messaging.OutboxWriter;
import com.retail.oms.order.infrastructure.persistence.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order use cases. Every state change and its outbox event are written in ONE database transaction:
 * either both are committed or neither is (no "order saved but event lost", no "event without order").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final OrderRepository orders;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    /** Result of a create call; {@code replayed} is true when the idempotency key already had an order. */
    public record CreateResult(Order order, boolean replayed) {
    }

    @Transactional
    public CreateResult create(CreateOrderCommand command, String idempotencyKey, String createdBy) {
        Optional<Order> existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Idempotency key already used by order {}, returning it", existing.get().getOrderNumber());
            return new CreateResult(existing.get(), true);
        }
        Instant now = clock.instant();
        String orderNumber = "OMS-%s-%06d".formatted(ORDER_DATE.format(now), orders.nextOrderNumber());
        Order order = Order.place(UUID.randomUUID(), orderNumber, command.customerId(), command.channel(),
                command.currency(), command.lines(), command.addresses(), command.shippingTotal(), idempotencyKey,
                createdBy, now);
        order.usePaymentMethod(command.paymentMethod());
        if (command.expectedGrandTotal() != null && command.expectedGrandTotal().compareTo(order.getGrandTotal()) != 0) {
            throw new OrderTotalsMismatchException(command.expectedGrandTotal(), order.getGrandTotal());
        }
        order = orders.save(order);
        outboxWriter.orderCreated(order);
        log.info("Order {} created for customer {} total {} {}", orderNumber, command.customerId(),
                order.getGrandTotal(), order.getCurrency());
        return new CreateResult(order, false);
    }

    @Transactional(readOnly = true)
    public Order get(UUID orderId) {
        return orders.findDetailsById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /** Customer or agent cancel. Cancelling an already cancelled order is a no-op. */
    @Transactional
    public Order cancel(UUID orderId, String reason, String changedBy) {
        Order order = get(orderId);
        if (order.getStatus() != OrderStatus.CANCELLED && !order.getStatus().isCancellable()) {
            throw new InvalidOrderStateException("Order cannot be cancelled after shipment");
        }
        return applyStatus(order, OrderStatus.CANCELLED, reason, changedBy);
    }

    /** Used by the saga (PATCH /internal/...). Repeating the same command is a no-op, so retries are safe. */
    @Transactional
    public Order changeStatus(UUID orderId, OrderStatus target, String reason, String changedBy) {
        return applyStatus(get(orderId), target, reason, changedBy);
    }

    private Order applyStatus(Order order, OrderStatus target, String reason, String changedBy) {
        try {
            order.changeStatus(target, reason, changedBy, clock.instant())
                    .ifPresent(change -> {
                        outboxWriter.statusChanged(order, change);
                        log.info("Order {} {} -> {} by {}", order.getOrderNumber(), change.from(), change.to(), changedBy);
                    });
            return order;
        } catch (IllegalStateException e) {
            throw new InvalidOrderStateException(e.getMessage());
        }
    }
}
