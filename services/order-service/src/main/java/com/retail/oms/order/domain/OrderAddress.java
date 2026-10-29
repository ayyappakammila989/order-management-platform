package com.retail.oms.order.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_addresses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_addresses_seq")
    @SequenceGenerator(name = "order_addresses_seq", sequenceName = "order_addresses_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Enumerated(EnumType.STRING)
    private AddressType type;

    private String name;
    private String line1;
    private String city;
    private String state;
    private String zip;

    OrderAddress(Order order, Address address) {
        this.order = order;
        this.type = address.type();
        this.name = address.name();
        this.line1 = address.line1();
        this.city = address.city();
        this.state = address.state();
        this.zip = address.zip();
    }

    /** Never log the full address (architecture 14.3); toString shows only type and ZIP. */
    @Override
    public String toString() {
        return "OrderAddress[" + type + ", zip=" + zip + "]";
    }
}
