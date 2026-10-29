package com.retail.oms.cart.infrastructure.persistence;

import com.retail.oms.cart.domain.CartItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "saved_cart")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedCart {

    @Id
    @Column(name = "saved_cart_id")
    private UUID savedCartId;
    @Column(name = "customer_id")
    private String customerId;
    private String name;
    private String currency;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<CartItem> items;
    @Column(name = "created_at")
    private Instant createdAt;

    public SavedCart(UUID savedCartId, String customerId, String name, String currency, List<CartItem> items,
            Instant createdAt) {
        this.savedCartId = savedCartId;
        this.customerId = customerId;
        this.name = name;
        this.currency = currency;
        this.items = items;
        this.createdAt = createdAt;
    }
}
