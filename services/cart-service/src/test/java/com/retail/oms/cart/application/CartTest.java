package com.retail.oms.cart.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.retail.oms.cart.domain.Cart;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CartTest {

    static final Instant T0 = Instant.parse("2026-10-29T10:00:00Z");
    static final Instant T1 = T0.plusSeconds(60);

    @Test
    void addingTheSameSkuTwiceIncreasesTheQuantityInsteadOfAddingALine() {
        Cart cart = Cart.empty("cust-1", "USD", T0).addItem("SKU-1", 2, T0).addItem("SKU-2", 1, T0).addItem("SKU-1", 3, T1);

        assertThat(cart.items()).hasSize(2);
        assertThat(cart.items().get(0).sku()).isEqualTo("SKU-1");
        assertThat(cart.items().get(0).quantity()).isEqualTo(5);
        assertThat(cart.updatedAt()).isEqualTo(T1);
        assertThat(cart.createdAt()).isEqualTo(T0);
    }

    @Test
    void changeAndRemoveWorkOnTheItemId() {
        Cart cart = Cart.empty("cust-1", "USD", T0).addItem("SKU-1", 2, T0).addItem("SKU-2", 1, T0);
        String first = cart.items().get(0).itemId();
        String second = cart.items().get(1).itemId();

        Cart changed = cart.changeQuantity(first, 7, T1);
        assertThat(changed.item(first).orElseThrow().quantity()).isEqualTo(7);
        assertThat(changed.item(second).orElseThrow().quantity()).isEqualTo(1);

        Cart removed = changed.removeItem(first, T1);
        assertThat(removed.items()).extracting("sku").containsExactly("SKU-2");
    }

    @Test
    void cartsAreImmutable() {
        Cart original = Cart.empty("cust-1", "USD", T0);
        original.addItem("SKU-1", 1, T0);
        assertThat(original.items()).isEmpty();
    }
}
