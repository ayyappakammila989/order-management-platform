package com.retail.oms.cart.api.dto;

import com.retail.oms.cart.domain.Cart;
import com.retail.oms.cart.domain.CartItem;
import com.retail.oms.cart.infrastructure.persistence.SavedCart;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the cart API. */
public final class CartDtos {

    private CartDtos() {
    }

    public record CreateCartRequest(@Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like USD")
                                    String currency) {
    }

    public record AddItemRequest(@NotBlank @Size(max = 40) String sku, @Min(1) @Max(999) int quantity) {
    }

    public record ChangeQuantityRequest(@Min(1) @Max(999) int quantity) {
    }

    public record AddressRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 200) String line1,
                                 @NotBlank @Size(max = 100) String city, @NotBlank @Size(max = 50) String state,
                                 @NotBlank @Pattern(regexp = "\\d{5}(-\\d{4})?", message = "must be a US ZIP")
                                 String zip) {
        @Override
        public String toString() {
            return "AddressRequest[zip=" + zip + "]";
        }
    }

    public record CheckoutRequest(@NotNull @Valid AddressRequest shippingAddress, @Size(max = 40) String couponCode,
                                  @Pattern(regexp = "WEB|MOBILE|STORE|CALL_CENTER") String channel) {
    }

    public record SaveCartRequest(@NotBlank @Size(max = 80) String name) {
    }

    public record CartResponse(String cartId, String customerId, String currency, List<CartItem> items,
                               Instant createdAt, Instant updatedAt) {
        public static CartResponse of(Cart cart) {
            return new CartResponse(cart.cartId(), cart.customerId(), cart.currency(), cart.items(),
                    cart.createdAt(), cart.updatedAt());
        }
    }

    public record SavedCartResponse(UUID savedCartId, String name, String currency, List<CartItem> items,
                                    Instant createdAt) {
        public static SavedCartResponse of(SavedCart saved) {
            return new SavedCartResponse(saved.getSavedCartId(), saved.getName(), saved.getCurrency(),
                    saved.getItems(), saved.getCreatedAt());
        }
    }

    public record CheckoutResponse(UUID orderId, String orderNumber, String status, String currency,
                                   java.math.BigDecimal grandTotal, boolean replayed) {
    }
}
