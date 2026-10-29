package com.retail.oms.cart.application;

import com.retail.oms.cart.domain.Cart;
import java.util.Optional;

/** Where active carts live (Redis). A write succeeds only if the cart still has the version that was read. */
public interface CartStore {

    record VersionedCart(Cart cart, long version) {
    }

    void create(Cart cart);

    /** Returns the cart and pushes its expiry out again (sliding 7 days). */
    Optional<VersionedCart> find(String cartId);

    /** @return false when the cart changed (or vanished) since {@code expectedVersion} was read */
    boolean replace(Cart cart, long expectedVersion);

    void delete(String cartId);
}
