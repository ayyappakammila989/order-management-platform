package com.retail.oms.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.retail.oms.order.domain.AddressType;
import com.retail.oms.order.domain.Channel;
import com.retail.oms.order.domain.FulfillmentType;
import com.retail.oms.order.domain.Order;
import com.retail.oms.order.domain.OrderStatus;
import com.retail.oms.order.exception.InvalidOrderStateException;
import com.retail.oms.order.exception.OrderNotFoundException;
import com.retail.oms.order.exception.OrderTotalsMismatchException;
import com.retail.oms.order.infrastructure.messaging.OutboxWriter;
import com.retail.oms.order.infrastructure.persistence.OrderRepository;
import com.retail.oms.order.support.TestOrders;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orders;
    @Mock OutboxWriter outboxWriter;

    OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, outboxWriter, Clock.fixed(TestOrders.NOW, ZoneOffset.UTC));
    }

    @Test
    void createSavesOrderAndWritesOutboxEvent() {
        when(orders.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(orders.nextOrderNumber()).thenReturn(42L);
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrderService.CreateResult result = service.create(command(null), "key-1", "cart-service");

        Order order = result.order();
        assertThat(result.replayed()).isFalse();
        assertThat(order.getOrderNumber()).isEqualTo("OMS-20261029-000042");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getGrandTotal()).isEqualByComparingTo("51.37");
        assertThat(order.getCreatedAt()).isEqualTo(TestOrders.NOW);
        assertThat(order.getStatusHistory()).singleElement()
                .satisfies(h -> assertThat(h.getChangedBy()).isEqualTo("cart-service"));
        InOrder sequence = inOrder(orders, outboxWriter);
        sequence.verify(orders).save(order);              // ids assigned before the event is built
        sequence.verify(outboxWriter).orderCreated(order);
    }

    @Test
    void knownIdempotencyKeyReturnsExistingOrderWithoutNewEvent() {
        Order existing = TestOrders.order("cust-1", "key-2");
        when(orders.findByIdempotencyKey("key-2")).thenReturn(Optional.of(existing));

        OrderService.CreateResult result = service.create(command(null), "key-2", "cart-service");

        assertThat(result.replayed()).isTrue();
        assertThat(result.order()).isSameAs(existing);
        verify(orders, never()).save(any());
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void createRefusesWhenCartTotalDiffersFromLines() {
        when(orders.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(command(new BigDecimal("50.00")), "key-3", "cart-service"))
                .isInstanceOf(OrderTotalsMismatchException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ORD-422");
        verify(orders, never()).save(any());
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void createAcceptsMatchingExpectedTotal() {
        when(orders.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create(command(new BigDecimal("51.37")), "key-4", "cart-service").order().getGrandTotal())
                .isEqualByComparingTo("51.37");
    }

    @Test
    void cancelIsRejectedAfterShipment() {
        Order shipped = orderIn(OrderStatus.SHIPPED);

        assertThatThrownBy(() -> service.cancel(shipped.getOrderId(), "changed my mind", "cust-1"))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessage("Order cannot be cancelled after shipment")
                .hasFieldOrPropertyWithValue("errorCode", "ORD-409");
        assertThat(shipped.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        verify(outboxWriter, never()).statusChanged(any(), any());
    }

    @Test
    void cancelPendingOrderWritesCancelledEvent() {
        Order pending = orderIn(OrderStatus.PENDING);

        service.cancel(pending.getOrderId(), "changed my mind", "cust-1");

        ArgumentCaptor<Order.StatusChange> change = ArgumentCaptor.forClass(Order.StatusChange.class);
        verify(outboxWriter).statusChanged(any(), change.capture());
        assertThat(change.getValue()).isEqualTo(new Order.StatusChange(OrderStatus.PENDING, OrderStatus.CANCELLED,
                "changed my mind", "cust-1", TestOrders.NOW));
        assertThat(pending.getStatusHistory()).hasSize(2);
    }

    @Test
    void cancellingTwiceIsANoOp() {
        Order cancelled = orderIn(OrderStatus.CANCELLED);

        service.cancel(cancelled.getOrderId(), "again", "cust-1");

        verify(outboxWriter, never()).statusChanged(any(), any());
    }

    @Test
    void sagaRetryWithSameStatusIsANoOp() {
        Order confirmed = orderIn(OrderStatus.CONFIRMED);

        service.changeStatus(confirmed.getOrderId(), OrderStatus.CONFIRMED, "retry", "order-orchestrator");

        verify(outboxWriter, never()).statusChanged(any(), any());
    }

    @Test
    void invalidTransitionIsAConflict() {
        Order pending = orderIn(OrderStatus.PENDING);

        assertThatThrownBy(() -> service.changeStatus(pending.getOrderId(), OrderStatus.DELIVERED, null, "x"))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("cannot move from PENDING to DELIVERED");
    }

    @Test
    void unknownOrderIsNotFound() {
        UUID id = UUID.randomUUID();
        when(orders.findDetailsById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(OrderNotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ORD-404");
    }

    private Order orderIn(OrderStatus status) {
        Order order = TestOrders.order("cust-1");
        switch (status) {
            case PENDING -> { }
            case CONFIRMED -> order.changeStatus(OrderStatus.CONFIRMED, null, "saga", TestOrders.NOW);
            case SHIPPED -> {
                order.changeStatus(OrderStatus.CONFIRMED, null, "saga", TestOrders.NOW);
                order.changeStatus(OrderStatus.SHIPPED, null, "wms", TestOrders.NOW);
            }
            case CANCELLED -> order.changeStatus(OrderStatus.CANCELLED, null, "saga", TestOrders.NOW);
            default -> throw new IllegalArgumentException(status.name());
        }
        when(orders.findDetailsById(order.getOrderId())).thenReturn(Optional.of(order));
        return order;
    }

    private static CreateOrderCommand command(BigDecimal expectedGrandTotal) {
        return new CreateOrderCommand("cust-1", Channel.WEB, "USD",
                List.of(new Order.NewLine("SKU-1", 2, new BigDecimal("19.99"), new BigDecimal("2.00"),
                                new BigDecimal("3.00"), FulfillmentType.SHIP),
                        new Order.NewLine("SKU-2", 1, new BigDecimal("5.00"), BigDecimal.ZERO,
                                new BigDecimal("0.40"), FulfillmentType.SHIP)),
                List.of(TestOrders.address(AddressType.SHIP)), new BigDecimal("4.99"), expectedGrandTotal, null);
    }
}
