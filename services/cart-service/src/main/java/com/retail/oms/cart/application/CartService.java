package com.retail.oms.cart.application;

import com.retail.oms.cart.application.CartStore.VersionedCart;
import com.retail.oms.cart.config.CartProperties;
import com.retail.oms.cart.domain.Cart;
import com.retail.oms.cart.exception.CartConflictException;
import com.retail.oms.cart.exception.CartNotFoundException;
import com.retail.oms.cart.exception.CartRuleException;
import java.time.Clock;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CartService {

    private static final int MAX_ATTEMPTS = 10;

    private final CartStore store;
    private final CartProperties props;
    private final Clock clock;

    public Cart create(String customerId, String currency) {
        Cart cart = Cart.empty(customerId, currency == null ? props.defaultCurrency() : currency, clock.instant());
        store.create(cart);
        return cart;
    }

    public Cart get(String cartId, String customerId) {
        return load(cartId, customerId).cart();
    }

    public Cart addItem(String cartId, String customerId, String sku, int quantity) {
        return change(cartId, customerId, cart -> {
            Cart next = cart.addItem(sku, quantity, clock.instant());
            if (next.items().size() > props.maxLines()) {
                throw new CartRuleException("A cart can hold at most " + props.maxLines() + " different items");
            }
            if (next.items().stream().anyMatch(i -> i.quantity() > props.maxQuantityPerLine())) {
                throw new CartRuleException("At most " + props.maxQuantityPerLine() + " units of one item");
            }
            return next;
        });
    }

    public Cart changeQuantity(String cartId, String customerId, String itemId, int quantity) {
        return change(cartId, customerId, cart -> {
            requireItem(cart, itemId);
            return cart.changeQuantity(itemId, quantity, clock.instant());
        });
    }

    public Cart removeItem(String cartId, String customerId, String itemId) {
        return change(cartId, customerId, cart -> {
            requireItem(cart, itemId);
            return cart.removeItem(itemId, clock.instant());
        });
    }

    public void delete(String cartId) {
        store.delete(cartId);
    }

    VersionedCart load(String cartId, String customerId) {
        return store.find(cartId).filter(v -> v.cart().customerId().equals(customerId))
                .orElseThrow(() -> new CartNotFoundException(cartId));
    }

    /** Read, change, write only if unchanged; if another request got in first, read again and redo the change. */
    private Cart change(String cartId, String customerId, UnaryOperator<Cart> change) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            VersionedCart current = load(cartId, customerId);
            Cart next = change.apply(current.cart());
            if (store.replace(next, current.version())) {
                return next;
            }
        }
        throw new CartConflictException();
    }

    private static void requireItem(Cart cart, String itemId) {
        if (cart.item(itemId).isEmpty()) {
            throw new CartRuleException("Item " + itemId + " is not in this cart");
        }
    }
}
