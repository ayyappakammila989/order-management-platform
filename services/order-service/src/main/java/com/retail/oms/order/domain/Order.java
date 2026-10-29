package com.retail.oms.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Aggregate root: lines, addresses and status history are only changed through this class,
 * so the totals and the status rules can never be bypassed.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    private UUID orderId;

    private String orderNumber;
    private String customerId;

    @Enumerated(EnumType.STRING)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private String currency;
    private BigDecimal subtotal;
    private BigDecimal discountTotal;
    private BigDecimal taxTotal;
    private BigDecimal shippingTotal;
    private BigDecimal grandTotal;
    private String sapOrderNumber;

    @Column(updatable = false)
    private String idempotencyKey;

    @Column(updatable = false)
    private Instant createdAt;
    private Instant updatedAt;

    /** Optimistic locking: two concurrent status changes cannot both win. */
    @Version
    private long version;

    // Sets, not Lists: fetching two collections in one join returns lines x addresses rows, and a List (bag)
    // would keep the duplicates. @OrderBy keeps the lines in insertion order.
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineId")
    private Set<OrderLine> lines = new LinkedHashSet<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<OrderAddress> addresses = new LinkedHashSet<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    /** Line data handed in by the application layer. Amounts are per line (discount and tax for the whole line). */
    public record NewLine(String sku, int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal tax,
            FulfillmentType fulfillmentType) {
    }

    /** Result of a status change, used to write the matching outbox event. */
    public record StatusChange(OrderStatus from, OrderStatus to, String reason, String changedBy, Instant at) {
    }

    public static Order place(UUID orderId, String orderNumber, String customerId, Channel channel, String currency,
            List<NewLine> newLines, List<Address> addressList, BigDecimal shippingTotal, String idempotencyKey,
            String createdBy, Instant now) {
        if (newLines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        Order order = new Order();
        order.orderId = orderId;
        order.orderNumber = orderNumber;
        order.customerId = customerId;
        order.channel = channel;
        order.currency = currency;
        order.status = OrderStatus.PENDING;
        order.idempotencyKey = idempotencyKey;
        order.createdAt = now;
        order.updatedAt = now;
        for (NewLine l : newLines) {
            order.lines.add(new OrderLine(order, l.sku(), l.quantity(), money(l.unitPrice()), money(l.discount()),
                    money(l.tax()), l.fulfillmentType()));
        }
        for (Address a : addressList) {
            order.addresses.add(new OrderAddress(order, a));
        }
        order.shippingTotal = money(shippingTotal);
        order.recalculateTotals();
        order.statusHistory.add(new OrderStatusHistory(order, null, OrderStatus.PENDING, "Order placed", createdBy, now));
        return order;
    }

    /**
     * Moves the order to {@code next}. Returns empty when it is already there (a retried saga command
     * is a no-op, not an error).
     *
     * @throws IllegalStateException when the lifecycle does not allow the move
     */
    public Optional<StatusChange> changeStatus(OrderStatus next, String reason, String changedBy, Instant now) {
        if (status == next) {
            return Optional.empty();
        }
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("Order " + orderNumber + " cannot move from " + status + " to " + next);
        }
        OrderStatus from = status;
        status = next;
        updatedAt = now;
        statusHistory.add(new OrderStatusHistory(this, from, next, reason, changedBy, now));
        return Optional.of(new StatusChange(from, next, reason, changedBy, now));
    }

    public Optional<OrderAddress> address(AddressType type) {
        return addresses.stream().filter(a -> a.getType() == type).findFirst();
    }

    public List<OrderLine> getLines() {
        return List.copyOf(lines);
    }

    public Set<OrderAddress> getAddresses() {
        return Collections.unmodifiableSet(addresses);
    }

    public List<OrderStatusHistory> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }

    private void recalculateTotals() {
        subtotal = money(lines.stream().map(OrderLine::extendedPrice).reduce(BigDecimal.ZERO, BigDecimal::add));
        discountTotal = money(lines.stream().map(OrderLine::getDiscount).reduce(BigDecimal.ZERO, BigDecimal::add));
        taxTotal = money(lines.stream().map(OrderLine::getTax).reduce(BigDecimal.ZERO, BigDecimal::add));
        grandTotal = subtotal.subtract(discountTotal).add(taxTotal).add(shippingTotal);
    }

    /** Amounts arrive already priced; anything with more than 2 decimals is a pricing bug, so fail rather than round. */
    static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.UNNECESSARY);
    }
}
