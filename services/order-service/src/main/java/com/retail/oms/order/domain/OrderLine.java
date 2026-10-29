package com.retail.oms.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_lines")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_lines_seq")
    @SequenceGenerator(name = "order_lines_seq", sequenceName = "order_lines_seq", allocationSize = 50)
    @Column(name = "line_id")
    private Long lineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    private String sku;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal discount;
    private BigDecimal tax;

    /** Per-line status; stays OPEN until fulfillment (step 4) splits shipments. */
    private String lineStatus;

    @Enumerated(EnumType.STRING)
    private FulfillmentType fulfillmentType;

    OrderLine(Order order, String sku, int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal tax,
            FulfillmentType fulfillmentType) {
        this.order = order;
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.discount = discount;
        this.tax = tax;
        this.fulfillmentType = fulfillmentType;
        this.lineStatus = "OPEN";
    }

    /** quantity x unit price, before discount and tax. */
    public BigDecimal extendedPrice() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
