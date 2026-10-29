package com.retail.oms.cart.domain;

public record CartItem(String itemId, String sku, int quantity) {

    public CartItem withQuantity(int newQuantity) {
        return new CartItem(itemId, sku, newQuantity);
    }
}
