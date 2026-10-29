package com.retail.oms.cart.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retail.oms.cart.application.CartService;
import com.retail.oms.cart.application.SavedCartService;
import com.retail.oms.cart.domain.Cart;
import com.retail.oms.cart.exception.CartNotFoundException;
import com.retail.oms.cart.exception.CartRuleException;
import com.retail.oms.cart.infrastructure.persistence.SavedCart;
import com.retail.oms.cart.support.PostgresRedisTestConfig;
import com.retail.oms.cart.support.ServiceTokenTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Saved carts in real PostgreSQL (Flyway migration, JSONB items) and restore into a fresh Redis cart. */
@SpringBootTest
@Import({PostgresRedisTestConfig.class, ServiceTokenTestConfig.class})
class SavedCartIT {

    @Autowired CartService carts;
    @Autowired SavedCartService savedCarts;

    @Test
    void saveListRestoreAndDelete() {
        Cart cart = carts.create("cust-saved", "USD");
        carts.addItem(cart.cartId(), "cust-saved", "SKU-1", 2);
        carts.addItem(cart.cartId(), "cust-saved", "SKU-2", 1);

        SavedCart saved = savedCarts.save(cart.cartId(), "cust-saved", "Birthday");

        assertThat(savedCarts.list("cust-saved")).singleElement().satisfies(s -> {
            assertThat(s.getName()).isEqualTo("Birthday");
            assertThat(s.getItems()).extracting("sku", "quantity").containsExactly(
                    org.assertj.core.groups.Tuple.tuple("SKU-1", 2), org.assertj.core.groups.Tuple.tuple("SKU-2", 1));
        });

        Cart restored = savedCarts.restore(saved.getSavedCartId(), "cust-saved");
        assertThat(restored.cartId()).isNotEqualTo(cart.cartId());
        assertThat(carts.get(restored.cartId(), "cust-saved").items()).hasSize(2);

        savedCarts.delete(saved.getSavedCartId(), "cust-saved");
        assertThat(savedCarts.list("cust-saved")).isEmpty();
    }

    @Test
    void otherCustomersCannotSeeOrRestoreIt() {
        Cart cart = carts.create("cust-a", "USD");
        carts.addItem(cart.cartId(), "cust-a", "SKU-1", 1);
        SavedCart saved = savedCarts.save(cart.cartId(), "cust-a", "Mine");

        assertThat(savedCarts.list("cust-b")).isEmpty();
        assertThatThrownBy(() -> savedCarts.restore(saved.getSavedCartId(), "cust-b")).isInstanceOf(CartNotFoundException.class);
        assertThatThrownBy(() -> savedCarts.delete(saved.getSavedCartId(), "cust-b")).isInstanceOf(CartNotFoundException.class);
    }

    @Test
    void emptyCartCannotBeSaved() {
        Cart cart = carts.create("cust-c", "USD");
        assertThatThrownBy(() -> savedCarts.save(cart.cartId(), "cust-c", "Nothing")).isInstanceOf(CartRuleException.class);
    }
}
