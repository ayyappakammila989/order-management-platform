package com.retail.oms.order.domain;

/** Address value handed to the domain from the API layer. */
public record Address(AddressType type, String name, String line1, String city, String state, String zip) {

    @Override
    public String toString() {
        return "Address[" + type + ", zip=" + zip + "]";
    }
}
