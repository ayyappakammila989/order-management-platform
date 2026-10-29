package com.retail.oms.cart.application;

import com.retail.oms.cart.domain.Cart;
import com.retail.oms.cart.domain.CartItem;
import com.retail.oms.cart.exception.CartNotFoundException;
import com.retail.oms.cart.exception.CartRuleException;
import com.retail.oms.cart.infrastructure.persistence.SavedCart;
import com.retail.oms.cart.infrastructure.persistence.SavedCartRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** "Save for later": active carts expire after 7 days in Redis, saved ones stay in PostgreSQL. */
@Service
@RequiredArgsConstructor
public class SavedCartService {

    private final CartService carts;
    private final CartStore store;
    private final SavedCartRepository repository;
    private final Clock clock;

    @Transactional
    public SavedCart save(String cartId, String customerId, String name) {
        Cart cart = carts.get(cartId, customerId);
        if (cart.items().isEmpty()) {
            throw new CartRuleException("An empty cart cannot be saved");
        }
        return repository.save(new SavedCart(UUID.randomUUID(), customerId, name, cart.currency(), cart.items(),
                clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<SavedCart> list(String customerId) {
        return repository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    /** Creates a new active cart with the saved items; the saved copy stays until it is deleted. */
    @Transactional(readOnly = true)
    public Cart restore(UUID savedCartId, String customerId) {
        SavedCart saved = find(savedCartId, customerId);
        Cart cart = Cart.empty(customerId, saved.getCurrency(), clock.instant());
        for (CartItem item : saved.getItems()) {
            cart = cart.addItem(item.sku(), item.quantity(), clock.instant());
        }
        store.create(cart);
        return cart;
    }

    @Transactional
    public void delete(UUID savedCartId, String customerId) {
        repository.delete(find(savedCartId, customerId));
    }

    private SavedCart find(UUID savedCartId, String customerId) {
        return repository.findBySavedCartIdAndCustomerId(savedCartId, customerId)
                .orElseThrow(() -> new CartNotFoundException(savedCartId.toString()));
    }
}
