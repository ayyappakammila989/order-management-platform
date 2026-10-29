package com.retail.oms.cart.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable: every change returns a new cart, which the store writes only if nobody else changed it meanwhile. */
public record Cart(String cartId, String customerId, String currency, List<CartItem> items, Instant createdAt,
                   Instant updatedAt) {

    public static Cart empty(String customerId, String currency, Instant now) {
        return new Cart(UUID.randomUUID().toString(), customerId, currency, List.of(), now, now);
    }

    public Optional<CartItem> item(String itemId) {
        return items.stream().filter(i -> i.itemId().equals(itemId)).findFirst();
    }

    /** Adding a SKU that is already in the cart increases that line's quantity. */
    public Cart addItem(String sku, int quantity, Instant now) {
        List<CartItem> next = new ArrayList<>(items);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).sku().equals(sku)) {
                next.set(i, next.get(i).withQuantity(next.get(i).quantity() + quantity));
                return with(next, now);
            }
        }
        next.add(new CartItem(UUID.randomUUID().toString(), sku, quantity));
        return with(next, now);
    }

    public Cart changeQuantity(String itemId, int quantity, Instant now) {
        return with(items.stream().map(i -> i.itemId().equals(itemId) ? i.withQuantity(quantity) : i).toList(), now);
    }

    public Cart removeItem(String itemId, Instant now) {
        return with(items.stream().filter(i -> !i.itemId().equals(itemId)).toList(), now);
    }

    private Cart with(List<CartItem> newItems, Instant now) {
        return new Cart(cartId, customerId, currency, List.copyOf(newItems), createdAt, now);
    }
}
