package com.retail.oms.query.application;

import static com.retail.oms.query.support.Events.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import com.retail.oms.query.support.Events;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderEventProcessorTest {

    @Mock OrderIndex index;
    @Mock ProcessedEvents processed;
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    OrderEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OrderEventProcessor(index, processed, meters);
    }

    @Test
    void newCreatedEventIsAppliedAndThenRemembered() {
        OrderCreatedEvent event = Events.created("o-1", "cust-1", T0);

        processor.process(event);

        InOrder order = inOrder(index, processed);
        order.verify(index).applyCreated(event);
        order.verify(processed).markProcessed(event.getEventId());      // remembered only after it was applied
        assertThat(meters.counter("oms.query.events.applied").count()).isEqualTo(1);
    }

    @Test
    void newStatusEventIsApplied() {
        OrderStatusChangedEvent event = Events.status("e-1", "o-1", "cust-1", "PENDING", "CANCELLED", T0);

        processor.process(event);

        verify(index).applyStatusChanged(event);
        verify(processed).markProcessed("e-1");
    }

    @Test
    void eventSeenBeforeIsSkippedAndCounted() {
        OrderStatusChangedEvent event = Events.status("e-1", "o-1", "cust-1", "PENDING", "CANCELLED", T0);
        when(processed.contains("e-1")).thenReturn(true);

        processor.process(event);

        verifyNoInteractions(index);
        verify(processed, never()).markProcessed(any());
        assertThat(meters.counter("oms.query.events.duplicate").count()).isEqualTo(1);
        assertThat(meters.counter("oms.query.events.applied").count()).isZero();
    }

    @Test
    void eventIsNotRememberedWhenApplyingItFails() {
        OrderStatusChangedEvent event = Events.status("e-1", "o-1", "cust-1", "PENDING", "CANCELLED", T0);
        doThrow(new IllegalStateException("elasticsearch down")).when(index).applyStatusChanged(event);

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class);

        verify(processed, never()).markProcessed(any());      // so the retry is not mistaken for a duplicate
    }

    @Test
    void eventsThatCanNeverBeIndexedAreRejectedAsInvalid() {
        assertThatThrownBy(() -> processor.process(Events.status("e-1", "", "cust-1", "PENDING", "CANCELLED", T0)))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("orderId");
        assertThatThrownBy(() -> processor.process(Events.status("e-1", "o-1", "cust-1", "PENDING", "not a status!", T0)))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("status");
        assertThatThrownBy(() -> processor.process(Events.status(" ", "o-1", "cust-1", "PENDING", "CANCELLED", T0)))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("eventId");
        assertThatThrownBy(() -> processor.process("some string"))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> processor.process(null)).isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(index);
    }

    @Test
    void statusNamesAddedLaterByOrderServiceAreAccepted() {
        processor.process(Events.status("e-9", "o-1", "cust-1", "CONFIRMED", "READY_FOR_PICKUP", T0));

        verify(index).applyStatusChanged(any());
    }
}
